package com.tracepilot.knowledge;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracepilot.rag")
public record RagProperties(boolean enabled, String embeddingModel, int dimensions, int topK,
                            double minimumSimilarity, BigDecimal embeddingPricePerMillion) {
    public static final String MODEL = "text-embedding-3-small";
    public static final int DIMENSIONS = 1536;

    public RagProperties {
        if (!MODEL.equals(embeddingModel) || dimensions != DIMENSIONS) {
            throw new IllegalArgumentException("Embedding model/dimension are fixed by migration V7.");
        }
        if (topK < 1 || topK > 5 || !Double.isFinite(minimumSimilarity)
            || minimumSimilarity < -1 || minimumSimilarity > 1
            || (embeddingPricePerMillion != null && embeddingPricePerMillion.signum() < 0)) {
            throw new IllegalArgumentException("Invalid RAG retrieval or embedding price settings.");
        }
    }
}
