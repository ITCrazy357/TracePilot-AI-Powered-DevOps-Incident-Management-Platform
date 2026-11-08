package com.tracepilot.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.analysis.AnalysisFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeSearch {
    private final KnowledgeService service;
    private final KnowledgeRepository repository;
    private final KnowledgeText text;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;

    public KnowledgeSearch(KnowledgeService service, KnowledgeRepository repository, KnowledgeText text,
                           EmbeddingClient embeddings, RagProperties properties) {
        this.service = service;
        this.repository = repository;
        this.text = text;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public KnowledgeDtos.SearchResponse search(UUID actorId, UUID workspaceId, KnowledgeDtos.SearchRequest request) {
        service.requireMember(actorId, workspaceId);
        service.requireRag();
        try {
            var matches = retrieve(workspaceId, text.sanitize(request.query()), request.topK(), request.mode(),
                Optional.empty(), 1);
            service.requireMember(actorId, workspaceId);
            return new KnowledgeDtos.SearchResponse(matches, request.mode(), RagProperties.MODEL);
        } catch (AnalysisFailure failure) {
            var status = "AI_DAILY_LIMIT".equals(failure.code()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Knowledge search could not be completed.");
        }
    }

    public List<KnowledgeDtos.Chunk> retrieve(UUID workspaceId, String query, int topK, KnowledgeDtos.SearchMode mode,
                                              Optional<UUID> analysisId, int attempt) {
        if (!repository.hasSearchable(workspaceId)) {
            return List.of();
        }
        // At most 2,000 UTF-16 units stay below the provider's 8,192-token limit even for UTF-8 worst cases.
        int start = Math.max(0, query.length() - 2000);
        if (start > 0 && Character.isLowSurrogate(query.charAt(start))) {
            start++;
        }
        var safeQuery = text.sanitize(query.substring(start));
        var vector = embeddings.embed(workspaceId, analysisId, Optional.empty(), attempt, List.of(safeQuery)).getFirst();
        return repository.search(workspaceId, vector, safeQuery, topK, mode, properties.minimumSimilarity());
    }
}
