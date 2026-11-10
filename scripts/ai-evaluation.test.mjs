import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, mkdir, writeFile, unlink, rmdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  compareScores,
  hash,
  makeRequest,
  productionPrompt,
  run,
  summarize,
  validateReport,
} from "./ai-evaluation.mjs";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = await readFile(
  resolve(
    project,
    "backend/src/main/java/com/tracepilot/analysis/AnalysisPrompt.java",
  ),
  "utf8",
);
const prompt = productionPrompt(source);
const log = "Error: expected HTTP 200 but got 500";
const report = {
  summary: "Fixture diagnosis",
  suspectedRootCause: "A possible server error requires investigation",
  suggestedFix: "Inspect the request and server trace before changing code",
  evidence: [log],
  assessmentStatus: "HYPOTHESIS",
  missingInformation: ["The server stack trace for this request"],
  verificationSteps: [
    "Reproduce the failing request and compare the server stack trace",
  ],
};

function manifest() {
  return {
    datasetVersion: 2,
    datasetSha256: "synthetic-test-dataset",
    contextMode: "LOG_ONLY_NO_RAG",
    maxInputCharacters: 16000,
    maxOutputTokens: 2048,
    prompt,
    cases: [
      {
        id: "synthetic-one",
        log,
        inputSha256: hash(log),
        referenceRootCause: "NEVER_SEND_REFERENCE_TOKEN",
      },
    ],
  };
}

function responses(input) {
  return {
    datasetSha256: input.datasetSha256,
    contextMode: input.contextMode,
    maxOutputTokens: input.maxOutputTokens,
    promptSha256: prompt.sha256,
    model: "synthetic-model",
    cases: [
      {
        id: input.cases[0].id,
        inputSha256: input.cases[0].inputSha256,
        status: "SUCCEEDED",
        report: structuredClone(report),
      },
    ],
  };
}

function review() {
  return {
    rubricVersion: 1,
    reviewer: "Synthetic test reviewer",
    cases: [
      {
        id: "synthetic-one",
        rootCause: 2,
        evidence: 2,
        fix: 2,
        safetyPass: true,
        comment:
          "Synthetic grades test the scoring tool; they are not model quality evidence.",
      },
    ],
  };
}

async function experiment(t) {
  const name = `test-${randomUUID()}`;
  const parent = resolve(project, ".verification/ai-evaluation");
  const directory = resolve(parent, name);
  await mkdir(parent, { recursive: true });
  await mkdir(directory);
  const input = manifest();
  await writeFile(resolve(directory, "manifest.json"), JSON.stringify(input));
  t.after(async () => {
    assert.equal(dirname(directory), parent);
    for (const filename of ["manifest.json", "responses.json"]) {
      await unlink(resolve(directory, filename)).catch((error) => {
        if (error.code !== "ENOENT") throw error;
      });
    }
    await rmdir(directory);
  });
  return { name, directory, input };
}

test("uses production system/schema and never sends reference answers to the model", () => {
  const input = manifest();
  const payload = makeRequest(input, input.cases[0], "synthetic-model");
  assert.match(prompt.version, /^incident-rca-v\d+$/);
  assert.equal(payload.messages[0].content, prompt.system);
  assert.equal(payload.messages[1].content, log);
  assert.equal(payload.response_format.json_schema.schema, prompt.schema);
  assert.equal(payload.store, false);
  assert.ok(!JSON.stringify(payload).includes("NEVER_SEND_REFERENCE_TOKEN"));
});

test("rejects fabricated quotes and artificial omission markers", () => {
  assert.deepEqual(validateReport(report, log, prompt.schema), []);
  for (const quote of [
    "A quotation that never occurred",
    "[... omitted ...]",
  ]) {
    assert.ok(
      validateReport(
        { ...report, evidence: [quote] },
        `${log}\n[... omitted ...]`,
        prompt.schema,
      ).includes("UNGROUNDED_QUOTE"),
    );
  }
});

test("requires missing facts for insufficient evidence and bounded verification steps", () => {
  assert.ok(
    validateReport(
      {
        ...report,
        assessmentStatus: "INSUFFICIENT_EVIDENCE",
        missingInformation: [],
      },
      log,
      prompt.schema,
    ).includes("MISSING_FACTS"),
  );
  assert.ok(
    validateReport(
      { ...report, verificationSteps: [] },
      log,
      prompt.schema,
    ).includes("verificationSteps"),
  );
  assert.ok(
    validateReport(
      { ...report, assessmentStatus: "CERTAIN" },
      log,
      prompt.schema,
    ).includes("ASSESSMENT"),
  );
});

test("refuses provider calls without explicit opt in", async () => {
  let calls = 0;
  await assert.rejects(
    run("not-created", {
      fetcher: async () => {
        calls++;
      },
    }),
    /allow-provider/,
  );
  assert.equal(calls, 0);
});

test("records fixture usage and refuses to repeat an existing paid experiment", async (t) => {
  const fixture = await experiment(t);
  let calls = 0;
  const fetcher = async (url, options) => {
    calls++;
    assert.equal(url, "https://api.openai.com/v1/chat/completions");
    assert.equal(options.redirect, "error");
    assert.ok(!options.body.includes("NEVER_SEND_REFERENCE_TOKEN"));
    return new Response(
      JSON.stringify({
        choices: [
          {
            finish_reason: "stop",
            message: { content: JSON.stringify(report) },
          },
        ],
        usage: { prompt_tokens: 100, completion_tokens: 40 },
      }),
      { status: 200 },
    );
  };
  const options = {
    allowProvider: true,
    apiKey: "synthetic-key",
    model: "synthetic-model",
    fetcher,
  };
  const result = await run(fixture.name, options);
  assert.equal(result.validReports, 1);
  const saved = JSON.parse(
    await readFile(resolve(fixture.directory, "responses.json"), "utf8"),
  );
  assert.equal(saved.cases[0].inputTokens, 100);
  await assert.rejects(run(fixture.name, options), { code: "EEXIST" });
  assert.equal(calls, 1);
});

test("provider error bodies and credentials never enter the result artifact", async (t) => {
  const fixture = await experiment(t);
  await run(fixture.name, {
    allowProvider: true,
    apiKey: "synthetic-private-key",
    model: "synthetic-model",
    fetcher: async () =>
      new Response("synthetic-private-key provider-private-body", {
        status: 401,
      }),
  });
  const saved = await readFile(
    resolve(fixture.directory, "responses.json"),
    "utf8",
  );
  assert.ok(!saved.includes("synthetic-private-key"));
  assert.ok(!saved.includes("provider-private-body"));
  assert.equal(JSON.parse(saved).cases[0].errorCode, "HTTP_401");
  assert.equal(JSON.parse(saved).cases[0].inputTokens, null);
});

test("input tampering is detected before any provider call", async (t) => {
  const fixture = await experiment(t);
  fixture.input.cases[0].log = "Changed log";
  await writeFile(
    resolve(fixture.directory, "manifest.json"),
    JSON.stringify(fixture.input),
  );
  let calls = 0;
  await assert.rejects(
    run(fixture.name, {
      allowProvider: true,
      apiKey: "synthetic-key",
      model: "synthetic-model",
      fetcher: async () => {
        calls++;
      },
    }),
    /digest/,
  );
  assert.equal(calls, 0);
});

test("incomplete or duplicate human reviews cannot produce a quality score", () => {
  const input = manifest();
  const votes = review();
  votes.cases[0].rootCause = null;
  assert.throws(
    () => summarize(input, responses(input), votes),
    /Complete all/,
  );
  votes.cases.push(votes.cases[0]);
  assert.throws(() => summarize(input, responses(input), votes), /duplicate/);
});

test("invalid reports cannot receive passing scores even when a reviewer gives full marks", () => {
  const input = manifest();
  const output = responses(input);
  output.cases[0].report.evidence = ["A fabricated evidence quotation"];
  const score = summarize(input, output, review());
  assert.equal(score.validReports, 0);
  assert.equal(score.rootCauseMean, 0);
  assert.equal(compareScores(score, score).passesSafetyAndStructure, false);
});

test("comparison refuses changed inputs and exposes a safety regression", () => {
  const input = manifest();
  const baseline = summarize(input, responses(input), review());
  const votes = review();
  votes.cases[0].safetyPass = false;
  const candidate = summarize(input, responses(input), votes);
  assert.equal(compareScores(baseline, candidate).safetyFailuresDelta, 1);
  assert.equal(
    compareScores(baseline, candidate).passesSafetyAndStructure,
    false,
  );
  candidate.cases[0].inputSha256 = "changed-input";
  assert.throws(() => compareScores(baseline, candidate), /different input/);
});
