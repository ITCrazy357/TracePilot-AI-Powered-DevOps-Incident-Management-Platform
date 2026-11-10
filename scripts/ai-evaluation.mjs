import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const artifactRoot = resolve(project, ".verification/ai-evaluation");
export const hash = (value) => createHash("sha256").update(value).digest("hex");
const readJson = async (path) => JSON.parse(await readFile(path, "utf8"));
const writeJson = (path, value, options = {}) =>
  writeFile(path, JSON.stringify(value, null, 2) + "\n", options);

function runDirectory(name) {
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(name ?? "")) {
    throw new Error(
      "Run name must use letters, digits, underscores or hyphens (1-80 characters).",
    );
  }
  return resolve(artifactRoot, name);
}

export function productionPrompt(source) {
  function block(name) {
    const match = source.match(
      new RegExp(`String ${name} = """\\r?\\n([\\s\\S]*?)""";`),
    );
    if (!match) throw new Error(`Cannot read production prompt ${name}.`);
    const lines = match[1].trimEnd().split(/\r?\n/);
    const indent = Math.min(
      ...lines
        .filter((line) => line.trim())
        .map((line) => line.match(/^ */)[0].length),
    );
    return lines.map((line) => line.slice(indent)).join("\n") + "\n";
  }
  const version = source.match(/VERSION = "([^"]+)"/)?.[1];
  if (!version) throw new Error("Cannot read production prompt version.");
  const system = block("SYSTEM");
  const schema = JSON.parse(block("SCHEMA"));
  return {
    version,
    system,
    schema,
    sha256: hash(JSON.stringify({ system, schema })),
  };
}

export async function prepare(name) {
  const datasetBytes = await readFile(
    resolve(
      project,
      "backend/src/test/resources/evaluation/analysis-quality-v2.json",
    ),
  );
  const dataset = JSON.parse(datasetBytes);
  const inputs = await readJson(
    resolve(project, "backend/target/phase11-evaluation-inputs.json"),
  );
  const selector = await readFile(
    resolve(
      project,
      "backend/src/main/java/com/tracepilot/incident/DiagnosticLogSelector.java",
    ),
  );
  const redactor = await readFile(
    resolve(
      project,
      "backend/src/main/java/com/tracepilot/incident/LogRedactor.java",
    ),
  );
  if (
    inputs.datasetSha256 !== hash(datasetBytes) ||
    inputs.selectorSha256 !== hash(selector) ||
    inputs.redactorSha256 !== hash(redactor) ||
    inputs.cases.length !== dataset.cases.length
  ) {
    throw new Error(
      "Evaluation inputs are stale. Run backend verify to regenerate production-selected inputs.",
    );
  }
  const prompt = productionPrompt(
    await readFile(
      resolve(
        project,
        "backend/src/main/java/com/tracepilot/analysis/AnalysisPrompt.java",
      ),
      "utf8",
    ),
  );
  const directory = runDirectory(name);
  await mkdir(artifactRoot, { recursive: true });
  await mkdir(directory); // Refuse to overwrite an existing experiment or review.
  await writeJson(resolve(directory, "manifest.json"), {
    ...inputs,
    prompt,
    maxOutputTokens: 2048,
  });
  await writeJson(resolve(directory, "references.json"), dataset);
  await writeJson(resolve(directory, "review.json"), {
    rubricVersion: 1,
    reviewer: "",
    scope:
      "Independent human review; never send reference answers to the model.",
    cases: inputs.cases.map(({ id }) => ({
      id,
      rootCause: null,
      evidence: null,
      fix: null,
      safetyPass: null,
      comment: "",
    })),
  });
  return {
    directory,
    cases: inputs.cases.length,
    promptVersion: prompt.version,
    contextMode: inputs.contextMode,
  };
}

export function makeRequest(manifest, item, model) {
  return {
    model,
    store: false,
    max_completion_tokens: manifest.maxOutputTokens,
    messages: [
      { role: "system", content: manifest.prompt.system },
      { role: "user", content: item.log },
    ],
    response_format: {
      type: "json_schema",
      json_schema: {
        name: "tracepilot_incident_evaluation",
        strict: true,
        schema: manifest.prompt.schema,
      },
    },
  };
}

// Structural/quote checks only. Human review must assess whether a diagnosis follows from its evidence.
export function validateReport(report, log, schema) {
  const errors = [];
  if (!report || Array.isArray(report) || typeof report !== "object")
    return ["REPORT_NOT_OBJECT"];
  if (
    JSON.stringify(Object.keys(report).sort()) !==
    JSON.stringify([...schema.required].sort())
  )
    errors.push("FIELDS");
  for (const [field, limit] of [
    ["summary", 2000],
    ["suspectedRootCause", 4000],
    ["suggestedFix", 4000],
  ]) {
    if (
      typeof report[field] !== "string" ||
      !report[field].trim() ||
      report[field].length > limit
    )
      errors.push(field);
  }
  if (
    !schema.properties.assessmentStatus.enum.includes(report.assessmentStatus)
  )
    errors.push("ASSESSMENT");
  for (const [field, minimum] of [
    ["evidence", 1],
    ["missingInformation", 0],
    ["verificationSteps", 1],
  ]) {
    const values = report[field];
    if (
      !Array.isArray(values) ||
      values.length < minimum ||
      values.length > 5
    ) {
      errors.push(field);
      continue;
    }
    if (new Set(values).size !== values.length)
      errors.push(`${field}:DUPLICATE`);
    for (const value of values) {
      if (
        typeof value !== "string" ||
        value.trim().length < 8 ||
        value.length > 500
      )
        errors.push(`${field}:LENGTH`);
      if (
        field === "evidence" &&
        (typeof value !== "string" ||
          !log.includes(value) ||
          value.includes("[... omitted ...]"))
      )
        errors.push("UNGROUNDED_QUOTE");
    }
  }
  if (
    report.assessmentStatus === "INSUFFICIENT_EVIDENCE" &&
    !report.missingInformation?.length
  )
    errors.push("MISSING_FACTS");
  return [...new Set(errors)];
}

async function boundedJson(response) {
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.length;
      if (size > 256 * 1024) throw new Error("RESPONSE_TOO_LARGE");
      chunks.push(Buffer.from(next.value));
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } finally {
    await reader.cancel();
  }
}

export async function run(
  name,
  {
    allowProvider = false,
    apiKey = process.env.AI_API_KEY,
    model = process.env.AI_MODEL,
    fetcher = fetch,
  } = {},
) {
  if (!allowProvider)
    throw new Error(
      "Provider calls require --allow-provider and can incur charges.",
    );
  if (!apiKey || !/^[A-Za-z0-9._:/-]{1,120}$/.test(model ?? ""))
    throw new Error("Set AI_API_KEY and AI_MODEL in the process environment.");
  const directory = runDirectory(name);
  const manifest = await readJson(resolve(directory, "manifest.json"));
  if (
    manifest.contextMode !== "LOG_ONLY_NO_RAG" ||
    manifest.maxOutputTokens !== 2048 ||
    hash(
      JSON.stringify({
        system: manifest.prompt.system,
        schema: manifest.prompt.schema,
      }),
    ) !== manifest.prompt.sha256
  ) {
    throw new Error("Invalid experiment manifest.");
  }
  if (
    !manifest.cases.length ||
    manifest.cases.length > 30 ||
    new Set(manifest.cases.map((item) => item.id)).size !==
      manifest.cases.length
  ) {
    throw new Error("Invalid experiment case set.");
  }
  for (const item of manifest.cases) {
    if (
      hash(item.log) !== item.inputSha256 ||
      item.log.length > manifest.maxInputCharacters
    )
      throw new Error("Input digest or size mismatch.");
  }
  const results = {
    datasetVersion: manifest.datasetVersion,
    datasetSha256: manifest.datasetSha256,
    contextMode: manifest.contextMode,
    promptVersion: manifest.prompt.version,
    promptSha256: manifest.prompt.sha256,
    model,
    maxOutputTokens: manifest.maxOutputTokens,
    cases: [],
  };
  const responsePath = resolve(directory, "responses.json");
  await writeJson(responsePath, results, { flag: "wx" }); // Never repeat a paid run implicitly.
  for (const item of manifest.cases) {
    const started = Date.now();
    let recorded = {
      id: item.id,
      inputSha256: item.inputSha256,
      status: "FAILED",
      inputTokens: null,
      outputTokens: null,
    };
    try {
      const response = await fetcher(
        "https://api.openai.com/v1/chat/completions",
        {
          method: "POST",
          redirect: "error",
          signal: AbortSignal.timeout(60000),
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${apiKey}`,
          },
          body: JSON.stringify(makeRequest(manifest, item, model)),
        },
      );
      if (!response.ok) {
        await response.body?.cancel();
        throw new Error(`HTTP_${response.status}`);
      }
      const body = await boundedJson(response);
      const tokens = (value) =>
        Number.isSafeInteger(value) && value >= 0 ? value : null;
      recorded.inputTokens = tokens(body.usage?.prompt_tokens);
      recorded.outputTokens = tokens(body.usage?.completion_tokens);
      if (
        body.choices?.length !== 1 ||
        body.choices[0].finish_reason !== "stop" ||
        body.choices[0].message.tool_calls
      )
        throw new Error("REFUSED_OR_TRUNCATED");
      const content = body.choices[0].message.content;
      if (typeof content !== "string" || content.length > 24000)
        throw new Error("INVALID_CONTENT");
      const report = JSON.parse(content.replaceAll(apiKey, "[REDACTED]"));
      const validationErrors = validateReport(
        report,
        item.log,
        manifest.prompt.schema,
      );
      recorded = {
        ...recorded,
        status: validationErrors.length ? "INVALID" : "SUCCEEDED",
        report,
        validationErrors,
      };
    } catch (error) {
      // Provider/transport errors can contain headers or response bodies; retain only an allowlisted code.
      recorded.errorCode =
        /^(HTTP_\d{3}|RESPONSE_TOO_LARGE|REFUSED_OR_TRUNCATED|INVALID_CONTENT)$/.test(
          error?.message,
        )
          ? error.message
          : "PROVIDER_OR_PROTOCOL_ERROR";
    }
    results.cases.push({ ...recorded, durationMs: Date.now() - started });
    await writeJson(responsePath, results);
  }
  return {
    directory,
    cases: results.cases.length,
    validReports: results.cases.filter((item) => item.status === "SUCCEEDED")
      .length,
  };
}

export function summarize(manifest, responses, review) {
  if (review.rubricVersion !== 1 || !review.reviewer?.trim())
    throw new Error("Supply rubricVersion=1 and an independent reviewer name.");
  if (!manifest.cases.length)
    throw new Error("Cannot score an empty experiment.");
  const expectedIds = manifest.cases.map((item) => item.id).sort();
  for (const list of [responses.cases, review.cases]) {
    if (
      JSON.stringify(list.map((item) => item.id).sort()) !==
      JSON.stringify(expectedIds)
    )
      throw new Error("Missing, duplicate or foreign case IDs.");
  }
  if (
    responses.datasetSha256 !== manifest.datasetSha256 ||
    responses.promptSha256 !== manifest.prompt.sha256 ||
    responses.contextMode !== manifest.contextMode ||
    responses.maxOutputTokens !== manifest.maxOutputTokens
  )
    throw new Error("Run does not match its frozen manifest.");
  const cases = manifest.cases.map((input) => {
    const response = responses.cases.find((item) => item.id === input.id);
    const vote = review.cases.find((item) => item.id === input.id);
    if (response.inputSha256 !== input.inputSha256)
      throw new Error("Response input digest mismatch.");
    if (
      ![vote.rootCause, vote.evidence, vote.fix].every(
        (value) => Number.isInteger(value) && value >= 0 && value <= 2,
      ) ||
      typeof vote.safetyPass !== "boolean" ||
      !vote.comment?.trim()
    )
      throw new Error(
        `Complete all rubric scores, safety and comment for ${input.id}.`,
      );
    const valid =
      response.status === "SUCCEEDED" &&
      validateReport(response.report, input.log, manifest.prompt.schema)
        .length === 0;
    return {
      id: input.id,
      inputSha256: input.inputSha256,
      valid,
      rootCause: valid ? vote.rootCause : 0,
      evidence: valid ? vote.evidence : 0,
      fix: valid ? vote.fix : 0,
      safetyPass: vote.safetyPass,
      comment: vote.comment,
    };
  });
  const mean = (field) =>
    cases.reduce((total, item) => total + item[field], 0) / cases.length;
  return {
    rubricVersion: 1,
    reviewer: review.reviewer,
    datasetVersion: manifest.datasetVersion,
    datasetSha256: manifest.datasetSha256,
    contextMode: manifest.contextMode,
    model: responses.model,
    promptVersion: manifest.prompt.version,
    promptSha256: manifest.prompt.sha256,
    caseCount: cases.length,
    maxOutputTokens: manifest.maxOutputTokens,
    validReports: cases.filter((item) => item.valid).length,
    safetyFailures: cases.filter((item) => !item.safetyPass).length,
    rootCauseMean: mean("rootCause"),
    evidenceMean: mean("evidence"),
    fixMean: mean("fix"),
    cases,
  };
}

export function compareScores(baseline, candidate) {
  for (const field of [
    "rubricVersion",
    "datasetSha256",
    "contextMode",
    "caseCount",
    "maxOutputTokens",
  ]) {
    if (baseline[field] !== candidate[field])
      throw new Error(`Cannot compare different ${field}.`);
  }
  const inputs = (report) =>
    report.cases
      .map(({ id, inputSha256 }) => ({ id, inputSha256 }))
      .sort((a, b) => a.id.localeCompare(b.id));
  if (JSON.stringify(inputs(baseline)) !== JSON.stringify(inputs(candidate)))
    throw new Error("Cannot compare different input snapshots.");
  return {
    caseCount: candidate.caseCount,
    contextMode: candidate.contextMode,
    baselineModel: baseline.model,
    candidateModel: candidate.model,
    baselinePromptVersion: baseline.promptVersion,
    candidatePromptVersion: candidate.promptVersion,
    passesSafetyAndStructure:
      candidate.safetyFailures === 0 &&
      candidate.validReports === candidate.caseCount,
    rootCauseMeanDelta: candidate.rootCauseMean - baseline.rootCauseMean,
    evidenceMeanDelta: candidate.evidenceMean - baseline.evidenceMean,
    fixMeanDelta: candidate.fixMean - baseline.fixMean,
    validReportsDelta: candidate.validReports - baseline.validReports,
    safetyFailuresDelta: candidate.safetyFailures - baseline.safetyFailures,
  };
}

async function main() {
  const [command, name, other] = process.argv.slice(2);
  if (command === "prepare") return prepare(name);
  if (command === "run")
    return run(name, { allowProvider: other === "--allow-provider" });
  if (command === "score") {
    const directory = runDirectory(name);
    const report = summarize(
      await readJson(resolve(directory, "manifest.json")),
      await readJson(resolve(directory, "responses.json")),
      await readJson(resolve(directory, "review.json")),
    );
    await writeJson(resolve(directory, "score.json"), report);
    return {
      ...report,
      cases: `${report.caseCount} individually reviewed cases`,
    };
  }
  if (command === "compare")
    return compareScores(
      await readJson(resolve(runDirectory(name), "score.json")),
      await readJson(resolve(runDirectory(other), "score.json")),
    );
  throw new Error(
    "Usage: node scripts/ai-evaluation.mjs prepare NAME | run NAME --allow-provider | score NAME | compare BASELINE CANDIDATE",
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  main()
    .then((result) => console.log(JSON.stringify(result, null, 2)))
    .catch((error) => {
      console.error(
        error.code === "ENOENT"
          ? "Missing input artifact. Run backend verify / prepare first."
          : error.message,
      );
      process.exitCode = 1;
    });
}
