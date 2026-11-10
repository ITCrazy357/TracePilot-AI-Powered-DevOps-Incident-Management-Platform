package com.tracepilot.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.analysis.AnalysisFailure;
import com.tracepilot.incident.DiagnosticLogSelector;
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
    private final SearchHistoryService history;
    private final DiagnosticLogSelector selector;

    public KnowledgeSearch(KnowledgeService service, KnowledgeRepository repository, KnowledgeText text,
                           EmbeddingClient embeddings, RagProperties properties, SearchHistoryService history,
                           DiagnosticLogSelector selector) {
        this.service = service;
        this.repository = repository;
        this.text = text;
        this.embeddings = embeddings;
        this.properties = properties;
        this.history = history;
        this.selector = selector;
    }

    public KnowledgeDtos.SearchResponse search(UUID actorId, UUID workspaceId, KnowledgeDtos.SearchRequest request) {
        service.requireMember(actorId, workspaceId);
        service.requireRag();
        var query = text.sanitize(request.query());
        if (query.isBlank() || query.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is empty or too long after redaction.");
        }
        try {
            var matches = retrieve(workspaceId, query, request.topK(), request.mode(),
                Optional.empty(), 1);
            var result = new KnowledgeDtos.SearchResponse(matches, request.mode(), RagProperties.MODEL);
            history.save(actorId, workspaceId, query, request.topK(), result);
            return result;
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
        var safeQuery = selector.select(text.sanitize(query), 2000);
        var vector = embeddings.embed(workspaceId, analysisId, Optional.empty(), attempt, List.of(safeQuery)).getFirst();
        return repository.search(workspaceId, vector, safeQuery, topK, mode, properties.minimumSimilarity());
    }
}
