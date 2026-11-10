package com.tracepilot.analysis;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.tracepilot.incident.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisOutputTests {
    private static final String INPUT = "Error: expected HTTP 200 but got 500";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties properties = new AiProperties(true, "opaque-configured-ai-key", "fixture-model",
        URI.create("https://api.openai.com/v1"), Duration.ofSeconds(1), 16000, 2048, 50, null, null);
    private final AnalysisOutput output = new AnalysisOutput(new LogRedactor(new MockEnvironment()), properties);

    @Test
    void acceptsAReportWithAnExactEvidenceQuoteAndMasksEchoedCredentials() {
        var result = output.parse(json("opaque-configured-ai-key", List.of(INPUT)), INPUT);
        assertThat(result.summary()).isEqualTo("[REDACTED]");
        assertThat(result.evidence()).containsExactly(INPUT);
    }

    @Test
    void rejectsInventedEvidenceAndUnknownFields() {
        assertInvalid(json("summary", List.of("fabricated evidence not in logs")));
        var value = mapper.readTree(json("summary", List.of(INPUT))).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) value).put("execute", "dangerous command");
        assertInvalid(value.toString());
    }

    @Test
    void rejectsMissingFieldsWrongTypesDuplicatesAndTrailingJson() {
        assertInvalid("{}");
        assertInvalid("{\"summary\":42}");
        assertInvalid(json("summary", List.of(INPUT)) + "{}");
        var duplicate = json("summary", List.of(INPUT)).replace("\"summary\":", "\"summary\":\"first\",\"summary\":");
        assertInvalid(duplicate);
    }

    @Test
    void insufficientEvidenceRequiresMissingFactsAndConcreteVerificationSteps() {
        var value = (tools.jackson.databind.node.ObjectNode) mapper.readTree(json("summary", List.of(INPUT)));
        value.put("assessmentStatus", "INSUFFICIENT_EVIDENCE");
        assertInvalid(value.toString());
        value.set("missingInformation", mapper.valueToTree(List.of("The original server stack trace")));
        var result = output.parse(value.toString(), INPUT);
        assertThat(result.assessmentStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.missingInformation()).containsExactly("The original server stack trace");
        value.set("verificationSteps", mapper.valueToTree(List.of()));
        assertInvalid(value.toString());
    }

    @Test
    void rejectsInventedAssessmentStatusesAndUnboundedOrRepeatedChecks() {
        var value = (tools.jackson.databind.node.ObjectNode) mapper.readTree(json("summary", List.of(INPUT)));
        value.put("assessmentStatus", "VERIFIED_100_PERCENT");
        assertInvalid(value.toString());
        value.put("assessmentStatus", "NO_FAILURE_INDICATED");
        value.set("verificationSteps", mapper.valueToTree(List.of("x".repeat(501))));
        assertInvalid(value.toString());
        value.set("verificationSteps", mapper.valueToTree(List.of("Check the failing step", "Check the failing step")));
        assertInvalid(value.toString());
    }

    @Test
    void rejectsBlankOversizedOrUnboundedEvidence() {
        assertInvalid(json(" ", List.of(INPUT)));
        assertInvalid(json("x".repeat(2001), List.of(INPUT)));
        assertInvalid(json("summary", List.of()));
        assertInvalid(json("summary", List.of(INPUT, INPUT)));
        assertInvalid("x".repeat(24001));
    }

    private String json(String summary, List<String> evidence) {
        return mapper.writeValueAsString(Map.of("summary", summary, "suspectedRootCause", "Uncertain root cause",
            "suggestedFix", "Human review required", "evidence", evidence, "assessmentStatus", "HYPOTHESIS",
            "missingInformation", List.of(), "verificationSteps", List.of("Reproduce the failing step and compare its actual result")));
    }

    private void assertInvalid(String content) {
        assertThatThrownBy(() -> output.parse(content, INPUT)).isInstanceOf(AnalysisFailure.class)
            .hasMessage("INVALID_AI_OUTPUT");
    }
}
