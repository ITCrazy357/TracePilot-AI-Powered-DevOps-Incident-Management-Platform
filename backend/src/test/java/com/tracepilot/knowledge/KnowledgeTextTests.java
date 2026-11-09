package com.tracepilot.knowledge;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.incident.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeTextTests {
    private final AiProperties ai = new AiProperties(false, "opaque-api-key-for-redaction", "",
        URI.create("https://api.openai.com/v1"), Duration.ofSeconds(1), 16000, 2048, 50, null, null);
    private final KnowledgeText text = new KnowledgeText(new LogRedactor(new MockEnvironment()), ai);

    @Test
    void redactsBeforeChunkingIncludingASecretAcrossTheChunkBoundary() {
        var resolution = "x".repeat(1150) + "opaque-api-key-for-redaction" + "\nUseful troubleshooting detail";
        var chunks = text.chunks(article(resolution));
        assertThat(String.join("", chunks)).doesNotContain("opaque-api-key-for-redaction")
            .contains("[REDACTED]", "Useful troubleshooting detail");
    }

    @Test
    void boundsChunksWithoutSplittingUnicodeSurrogatePairs() {
        var chunks = text.chunks(article("🚀x".repeat(2000)));
        assertThat(chunks.size()).isBetween(2, 12);
        for (var chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(1200);
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
        }
    }

    @Test
    void rejectsWrongDimensionZeroAndNonFiniteVectors() {
        assertThatThrownBy(() -> text.vector(new float[3])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> text.vector(new float[1536])).isInstanceOf(IllegalArgumentException.class);
        var vector = new float[1536];
        vector[0] = Float.NaN;
        assertThatThrownBy(() -> text.vector(vector)).isInstanceOf(IllegalArgumentException.class);
        vector[0] = 1;
        assertThat(text.vector(vector)).startsWith("[1.0,").endsWith("]");
    }

    @Test
    void rejectsEmbeddingModelOrDimensionChangesEvenWhenRagIsDisabled() {
        assertThatThrownBy(() -> new RagProperties(false, "other-model", 1536, 3, 0.35, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties(false, RagProperties.MODEL, 3, 3, 0.35, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeDtos.Article article(String resolution) {
        return new KnowledgeDtos.Article(UUID.randomUUID(), UUID.randomUUID(), null, "Reviewed fix",
            "Investigated root cause", resolution, "DRAFT", "NONE", 0, 0, UUID.randomUUID(),
            null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }
}
