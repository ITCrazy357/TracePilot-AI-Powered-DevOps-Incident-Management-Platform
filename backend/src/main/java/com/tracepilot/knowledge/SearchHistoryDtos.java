package com.tracepilot.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SearchHistoryDtos {
    private SearchHistoryDtos() {
    }

    public record Entry(UUID id, String query, KnowledgeDtos.SearchMode mode, int topK,
                        int resultCount, Instant createdAt) {
    }

    public record Detail(Entry search, KnowledgeDtos.SearchResponse result) {
    }

    public record Page(List<Entry> items, int page, int size, long total) {
    }
}
