package com.tracepilot.analysis;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.tracepilot.incident.DiagnosticLogSelector;
import com.tracepilot.incident.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisInputTests {
    private final UUID incidentId = UUID.randomUUID();
    private final AnalysisRepository repository = mock(AnalysisRepository.class);

    @Test
    void snapshotsRedactedContextAndHashesItWithTheLog() {
        when(repository.runContext(incidentId)).thenReturn("{\"workflow\":\"Build\"}");
        when(repository.logChunks(incidentId)).thenReturn(List.of("ERROR application failed\n"));
        var input = input(16000);
        var prepared = input.load(incidentId, "Java 21\npassword=private-value");
        assertThat(prepared.runContext()).contains("Build", "Java 21", "[REDACTED]").doesNotContain("private-value");
        assertThat(input.restore(incidentId, prepared.runContext())).isEqualTo(prepared);
        assertThat(input.load(incidentId, "Java 17").sha256()).isNotEqualTo(prepared.sha256());
    }

    @Test
    void configurationShrinkRejectsOldContextWithoutCrashingTheWorker() {
        when(repository.logChunks(incidentId)).thenReturn(List.of("ERROR application failed"));
        assertThatThrownBy(() -> input(100).restore(incidentId, "x".repeat(200)))
            .isInstanceOf(AnalysisFailure.class).hasMessage("AI_SOURCE_CHANGED");
    }

    private AnalysisInput input(int maximum) {
        var properties = new AiProperties(true, "fixture-key", "fixture-model", URI.create("https://api.openai.com/v1"),
            Duration.ofSeconds(1), maximum, 2048, 50, null, null);
        return new AnalysisInput(repository, new LogRedactor(new MockEnvironment()), properties, new DiagnosticLogSelector());
    }
}
