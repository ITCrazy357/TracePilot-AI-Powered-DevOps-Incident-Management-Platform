package com.tracepilot.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.tracepilot.incident.DiagnosticLogSelector;
import com.tracepilot.incident.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisEvaluationDatasetTests {
    @Test
    void exportsRedactedProductionSelectedInputsWithoutReferenceAnswers() throws Exception {
        var datasetPath = Path.of("src/test/resources/evaluation/analysis-quality-v2.json");
        var mapper = new ObjectMapper();
        var dataset = mapper.readTree(Files.readString(datasetPath));
        var selector = new DiagnosticLogSelector();
        var redactor = new LogRedactor(new MockEnvironment());
        var ids = new HashSet<String>();
        var inputs = new ArrayList<Map<String, String>>();
        assertThat(dataset.path("cases").size()).isBetween(20, 30);
        for (var item : dataset.path("cases")) {
            var id = item.path("id").asString();
            assertThat(ids.add(id)).as("Unique case %s", id).isTrue();
            assertThat(item.path("referenceRootCause").asString()).isNotBlank();
            assertThat(item.path("acceptableActions")).isNotEmpty();
            assertThat(item.path("unsafeActions")).isNotEmpty();
            var safe = redactor.redact(item.path("log").asString(), List.of());
            for (int budget : List.of(16000, 2000)) {
                var selected = selector.select(safe, budget);
                assertThat(selected.length()).as("Budget for %s", id).isLessThanOrEqualTo(budget);
                for (var quote : item.path("requiredEvidence")) {
                    assertThat(selected).as("Required signal in %s at budget %s", id, budget).contains(quote.asString());
                }
            }
            var log = selector.select(safe, 16000);
            inputs.add(Map.of("id", id, "log", log, "inputSha256", sha256(log.getBytes(StandardCharsets.UTF_8))));
        }
        var artifact = Map.of("datasetVersion", dataset.path("version").asInt(), "datasetSha256", sha256(Files.readAllBytes(datasetPath)),
            "selectorSha256", sha256(Files.readAllBytes(Path.of("src/main/java/com/tracepilot/incident/DiagnosticLogSelector.java"))),
            "redactorSha256", sha256(Files.readAllBytes(Path.of("src/main/java/com/tracepilot/incident/LogRedactor.java"))),
            "contextMode", "LOG_ONLY_NO_RAG", "maxInputCharacters", 16000, "cases", inputs);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/phase11-evaluation-inputs.json"), mapper.writeValueAsString(artifact));
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
