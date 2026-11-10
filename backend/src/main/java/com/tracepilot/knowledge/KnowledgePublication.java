package com.tracepilot.knowledge;

import java.util.Optional;
import java.util.UUID;

import com.tracepilot.analysis.AnalysisFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgePublication {
    private final KnowledgeService service;
    private final EmbeddingClient embeddings;

    public KnowledgePublication(KnowledgeService service, EmbeddingClient embeddings) {
        this.service = service;
        this.embeddings = embeddings;
    }

    public KnowledgeDtos.Article approve(UUID actorId, UUID workspaceId, UUID articleId, long version, boolean verified) {
        var publication = service.beginApproval(actorId, workspaceId, articleId, version, verified);
        try {
            var vectors = embeddings.embed(workspaceId, Optional.empty(), Optional.of(articleId), 1, publication.chunks());
            return service.publish(actorId, publication, vectors);
        } catch (AnalysisFailure failure) {
            service.publicationFailed(publication, failure.code());
            var status = "AI_DAILY_LIMIT".equals(failure.code()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Knowledge indexing failed. Reload before retrying approval.");
        } catch (ResponseStatusException failure) {
            service.publicationFailed(publication, "KNOWLEDGE_CHANGED");
            throw failure;
        }
    }
}
