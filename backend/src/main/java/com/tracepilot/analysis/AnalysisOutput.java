package com.tracepilot.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tracepilot.incident.LogRedactor;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AnalysisOutput {
    private static final Set<String> FIELDS = Set.of("summary", "suspectedRootCause", "suggestedFix", "evidence",
        "assessmentStatus", "missingInformation", "verificationSteps");
    private static final Set<String> ASSESSMENTS = Set.of("HYPOTHESIS", "INSUFFICIENT_EVIDENCE", "NO_FAILURE_INDICATED");
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final LogRedactor redactor;
    private final AiProperties properties;

    public AnalysisOutput(LogRedactor redactor, AiProperties properties) {
        this.redactor = redactor;
        this.properties = properties;
    }

    public AnalysisDtos.Report parse(String content, String input) {
        if (content == null || content.isBlank() || content.length() > 24000) {
            throw invalid();
        }
        try {
            var root = mapper.readTree(content);
            if (root == null || !root.isObject() || !root.propertyNames().equals(FIELDS)) {
                throw invalid();
            }
            var evidence = root.path("evidence");
            if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 5) {
                throw invalid();
            }
            var quotes = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (var quote : evidence) {
                var value = text(quote, 500);
                if (value.length() < 8 || !input.contains(value) || !seen.add(value)) {
                    throw invalid();
                }
                quotes.add(value);
            }
            var assessment = text(root.path("assessmentStatus"), 32);
            if (!ASSESSMENTS.contains(assessment)) {
                throw invalid();
            }
            var missing = items(root.path("missingInformation"), 0);
            var steps = items(root.path("verificationSteps"), 1);
            if ("INSUFFICIENT_EVIDENCE".equals(assessment) && missing.isEmpty()) {
                throw invalid();
            }
            return new AnalysisDtos.Report(text(root.path("summary"), 2000),
                text(root.path("suspectedRootCause"), 4000), text(root.path("suggestedFix"), 4000), List.copyOf(quotes),
                assessment, missing, steps);
        } catch (tools.jackson.core.JacksonException exception) {
            throw invalid();
        }
    }

    private List<String> items(JsonNode node, int minimum) {
        if (!node.isArray() || node.size() < minimum || node.size() > 5) {
            throw invalid();
        }
        var result = new ArrayList<String>();
        for (var item : node) {
            var value = text(item, 500);
            if (value.length() < 8 || result.contains(value)) {
                throw invalid();
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, int maximum) {
        if (!node.isString()) {
            throw invalid();
        }
        var value = redactor.redact(node.asString(), List.of(properties.apiKey())).strip();
        if (value.isBlank() || value.length() > maximum) {
            throw invalid();
        }
        return value;
    }

    private AnalysisFailure invalid() {
        return new AnalysisFailure("INVALID_AI_OUTPUT", true);
    }
}
