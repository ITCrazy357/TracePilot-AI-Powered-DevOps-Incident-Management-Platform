package com.tracepilot.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.tracepilot.incident.LogRedactor;
import org.springframework.stereotype.Component;

@Component
public class AnalysisInput {
    private final AnalysisRepository analysisRepository;
    private final LogRedactor redactor;
    private final AiProperties properties;

    public AnalysisInput(AnalysisRepository analysisRepository, LogRedactor redactor, AiProperties properties) {
        this.analysisRepository = analysisRepository;
        this.redactor = redactor;
        this.properties = properties;
    }

    public PreparedInput load(UUID incidentId) {
        var chunks = analysisRepository.logChunks(incidentId);
        var combined = String.join("", chunks);
        if (chunks.isEmpty() || chunks.size() > 9 || combined.length() > 32768 || combined.isBlank()) {
            throw new AnalysisFailure("LOGS_UNAVAILABLE", false);
        }
        // Join before redacting again: a newly configured secret may span two persisted snippets.
        var safe = redactor.redact(combined, List.of(properties.apiKey()));
        int start = Math.max(0, safe.length() - properties.maxInputCharacters());
        if (start > 0 && Character.isLowSurrogate(safe.charAt(start))) {
            start++;
        }
        safe = safe.substring(start);
        return new PreparedInput(safe, sha256(safe));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", exception);
        }
    }

    public record PreparedInput(String content, String sha256) {
    }
}
