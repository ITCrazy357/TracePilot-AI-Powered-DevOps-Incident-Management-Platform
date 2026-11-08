package com.tracepilot.incident;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTests {
    private final LogRedactor redactor = new LogRedactor(new MockEnvironment()
        .withProperty("tracepilot.github.webhook-secret", "configured-opaque-secret-12345"));

    @Test
    void preservesUsefulErrorsWhileMaskingHeadersAssignmentsUrlsAndTokenFormats() {
        var raw = """
            Error: expected 200 but got 500
            Authorization: Bearer raw-authorization
            Cookie: session=raw-cookie
            DB_PASSWORD=raw-password with spaces
            {"client_secret": "raw-json-secret", "count": 1}
            https://user:raw-url-password@host/path
            ghp_abcdefghijklmnop
            github_pat_abcdefghijklmnop
            AKIA1234567890123456
            eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123_def
            """;
        var safe = redactor.redact(raw, List.of());
        assertThat(safe).contains("Error: expected 200 but got 500", "[REDACTED]")
            .doesNotContain("raw-authorization", "raw-cookie", "raw-password", "raw-json-secret", "raw-url-password",
                "ghp_", "github_pat_", "AKIA123", "eyJhbGci");
    }

    @Test
    void masksMultilineAndUnterminatedSecretsBeforeAnyChunking() {
        var raw = "private_key=\"line-one\nline-two\"\nUseful error\n"
            + "-----BEGIN RSA PRIVATE KEY-----\nprivate-pem-data\n";
        assertThat(redactor.redact(raw, List.of())).contains("Useful error")
            .doesNotContain("line-one", "line-two", "private-pem-data");
        assertThat(redactor.redact("password=\"unterminated\nsensitive-tail", List.of()))
            .doesNotContain("unterminated", "sensitive-tail");
    }

    @Test
    void normalizesAnsiUnicodeAndControlCharactersBeforeDetection() {
        var raw = "pa\u001B[31mss\u001B[0mword=ansi-secret\n"
            + "ｔｏｋｅｎ=unicode-secret\n"
            + "ghp_abcd\u200Befghijklmnop\n"
            + "Authorization: \u001B]0;window-title\u0007Bearer hidden-value";
        assertThat(redactor.redact(raw, List.of()))
            .doesNotContain("ansi-secret", "unicode-secret", "ghp_", "hidden-value", "\u001B");
    }

    @Test
    void masksConfiguredAndTemporarySecretsIncludingEncodedVariants() {
        var encoded = Base64.getEncoder().encodeToString("configured-opaque-secret-12345".getBytes(StandardCharsets.UTF_8));
        assertThat(redactor.redact("configured-opaque-secret-12345\n" + encoded + "\nopaque-installation-value",
            List.of("opaque-installation-value")))
            .doesNotContain("configured-opaque-secret-12345", encoded, "opaque-installation-value");
    }

    @Test
    void doesNotSpendUnboundedTimeOnALargeOrdinaryLogLine() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
            () -> assertThat(redactor.redact("x".repeat(1024 * 1024), List.of())).hasSize(1024 * 1024));
    }

    @Test
    void masksAuthorizationInJsonAndBearerValuesInDiagnosticMessages() {
        var raw = "{\"Authorization\":\"Bearer json-header-secret\"}\n"
            + "Request used Bearer diagnostic-secret";
        assertThat(redactor.redact(raw, List.of()))
            .doesNotContain("json-header-secret", "diagnostic-secret");
    }

    @Test
    void escapedQuotesDoNotExposeTheRemainderOfAMultilineSecret() {
        var raw = "PASSWORD=\"first\\\"segment\nsecond-secret\"\nUseful failure";
        assertThat(redactor.redact(raw, List.of()))
            .contains("Useful failure").doesNotContain("first", "segment", "second-secret");
    }
}
