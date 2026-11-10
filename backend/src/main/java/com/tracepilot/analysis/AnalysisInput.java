package com.tracepilot.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.tracepilot.incident.LogRedactor;
import com.tracepilot.incident.DiagnosticLogSelector;
import org.springframework.stereotype.Component;

@Component
public class AnalysisInput {
    private final AnalysisRepository analysisRepository;
    private final LogRedactor redactor;
    private final AiProperties properties;
    private final DiagnosticLogSelector selector;

    public AnalysisInput(AnalysisRepository analysisRepository, LogRedactor redactor, AiProperties properties,
                         DiagnosticLogSelector selector) {
        this.analysisRepository = analysisRepository;
        this.redactor = redactor;
        this.properties = properties;
        this.selector = selector;
    }

    public PreparedInput load(UUID incidentId) {
        return load(incidentId, null);
    }

    public PreparedInput load(UUID incidentId, String additionalContext) {
        var context = "Run metadata (snapshot):\n" + analysisRepository.runContext(incidentId)
            + "\nUser-supplied context (unverified):\n" + (additionalContext == null ? "Not supplied" : additionalContext)
            + "\nRuntime versions, command, failed step and configuration are unknown unless explicitly present in the logs or supplied context.";
        context = redactor.redact(context, List.of(properties.apiKey()));
        int budget = Math.min(4000, Math.max(0, properties.maxInputCharacters() / 4 - 80));
        if (context.length() > budget) {
            int end = budget;
            if (end > 0 && Character.isHighSurrogate(context.charAt(end - 1))) {
                end--;
            }
            context = context.substring(0, end);
        }
        return restore(incidentId, context);
    }

    public PreparedInput restore(UUID incidentId, String runContext) {
        var chunks = analysisRepository.logChunks(incidentId);
        var combined = String.join("", chunks);
        if (chunks.isEmpty() || chunks.size() > 9 || combined.length() > 32768 || combined.isBlank()) {
            throw new AnalysisFailure("LOGS_UNAVAILABLE", false);
        }
        // Join before redacting again: a newly configured secret may span two persisted snippets.
        var safe = redactor.redact(combined, List.of(properties.apiKey()));
        var context = runContext == null ? "" : redactor.redact(runContext, List.of(properties.apiKey()));
        int logBudget = properties.maxInputCharacters() - context.length() - (context.isEmpty() ? 0 : 80);
        if (logBudget < 100) {
            throw new AnalysisFailure("AI_SOURCE_CHANGED", false);
        }
        safe = selector.select(safe, logBudget);
        return new PreparedInput(safe, sha256(safe + "\n" + context), context);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", exception);
        }
    }

    public record PreparedInput(String content, String sha256, String runContext) {
        public PreparedInput(String content, String sha256) {
            this(content, sha256, "");
        }
    }
}
