package com.tracepilot.incident;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticLogSelectorTests {
    private final DiagnosticLogSelector selector = new DiagnosticLogSelector();

    @Test
    void preservesEarlyErrorAndNestedCauseAheadOfLongCleanupOutput() {
        var input = "[INFO] Running ./mvnw verify\n[ERROR] Application startup failed\n"
            + "java.lang.IllegalStateException: Cannot start application\n"
            + "Caused by: org.postgresql.util.PSQLException: Connection refused\n"
            + "    at example.Database.connect(Database.java:42)\n"
            + "[INFO] Uploading artifact\n".repeat(2500) + "Process exited with code 1\n";
        for (int budget : List.of(32768, 16000, 2000)) {
            assertThat(selector.select(input, budget)).hasSizeLessThanOrEqualTo(budget)
                .contains("Application startup failed", "Connection refused", "Database.java:42", "exited with code 1");
        }
    }

    @Test
    void returnsShortLogsUnchangedAndUsesTailWhenThereAreNoDiagnosticSignals() {
        assertThat(selector.select("Short log\n", 100)).isEqualTo("Short log\n");
        assertThat(selector.select("x".repeat(500) + "tail", 100)).hasSize(100).endsWith("tail");
    }

    @Test
    void neverSplitsUnicodePairsEvenWithLongSingleLinesAndSmallBudgets() {
        var input = "ERROR " + "😀".repeat(3000) + "\nCaused by: " + "😀".repeat(3000) + "\ncleanup\n";
        for (int budget : List.of(100, 101, 2000, 2001)) {
            var result = selector.select(input, budget);
            assertThat(result).hasSizeLessThanOrEqualTo(budget);
            for (int index = 0; index < result.length(); index++) {
                if (Character.isHighSurrogate(result.charAt(index))) {
                    assertThat(index + 1).isLessThan(result.length());
                    assertThat(Character.isLowSurrogate(result.charAt(++index))).isTrue();
                } else {
                    assertThat(Character.isLowSurrogate(result.charAt(index))).isFalse();
                }
            }
        }
    }

    @Test
    void selectionAfterRedactionDoesNotExposeMultilineSecrets() {
        var raw = "private_key=\"ERROR hidden\nCaused by: secret\"\nERROR public failure\n"
            + "cleanup\n".repeat(1000);
        var safe = new LogRedactor(new MockEnvironment()).redact(raw, List.of());
        assertThat(selector.select(safe, 2000)).contains("public failure")
            .doesNotContain("hidden", "Caused by: secret");
    }
}
