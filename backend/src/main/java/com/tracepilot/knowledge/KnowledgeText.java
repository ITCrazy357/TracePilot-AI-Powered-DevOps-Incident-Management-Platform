package com.tracepilot.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.incident.LogRedactor;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeText {
    private static final int CHUNK_SIZE = 1200;
    private static final int OVERLAP = 120;
    private final LogRedactor redactor;
    private final AiProperties aiProperties;

    public KnowledgeText(LogRedactor redactor, AiProperties aiProperties) {
        this.redactor = redactor;
        this.aiProperties = aiProperties;
    }

    public String sanitize(String text) {
        return redactor.redact(text, List.of(aiProperties.apiKey())).strip();
    }

    public List<String> chunks(KnowledgeDtos.Article article) {
        var text = sanitize(article.title() + "\nSymptoms:\n" + article.symptoms()
            + "\nApplies to:\n" + article.applicability() + "\nRoot cause:\n" + article.rootCause()
            + "\nResolution:\n" + article.resolution() + "\nVerification:\n" + article.verification());
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = end - OVERLAP;
            if (Character.isLowSurrogate(text.charAt(start))) {
                start++;
            }
        }
        if (chunks.isEmpty() || chunks.size() > 12) {
            throw new IllegalArgumentException("Knowledge text exceeds the supported chunk limit.");
        }
        return List.copyOf(chunks);
    }

    public String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", exception);
        }
    }

    public String vector(float[] values) {
        if (values.length != RagProperties.DIMENSIONS) {
            throw new IllegalArgumentException("Embedding dimension does not match V7.");
        }
        var result = new StringBuilder("[");
        double norm = 0;
        for (int index = 0; index < values.length; index++) {
            if (!Float.isFinite(values[index])) {
                throw new IllegalArgumentException("Embedding contains a non-finite value.");
            }
            if (index > 0) {
                result.append(',');
            }
            result.append(values[index]);
            norm += (double) values[index] * values[index];
        }
        if (norm == 0) {
            throw new IllegalArgumentException("Zero embedding cannot be used for cosine search.");
        }
        return result.append(']').toString();
    }
}
