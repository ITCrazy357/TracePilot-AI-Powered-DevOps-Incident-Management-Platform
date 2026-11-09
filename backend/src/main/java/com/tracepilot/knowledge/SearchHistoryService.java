package com.tracepilot.knowledge;

import java.time.Clock;
import java.util.UUID;

import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SearchHistoryService {
    private final SearchHistoryRepository repository;
    private final KnowledgeService knowledge;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public SearchHistoryService(SearchHistoryRepository repository, KnowledgeService knowledge,
                                WorkspaceRepository workspaces, Clock clock) {
        this.repository = repository;
        this.knowledge = knowledge;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Transactional
    public void save(UUID actorId, UUID workspaceId, String query, int topK, KnowledgeDtos.SearchResponse result) {
        workspaces.findForUpdate(workspaceId).orElseThrow(this::notFound);
        knowledge.requireMember(actorId, workspaceId);
        repository.insert(UUID.randomUUID(), workspaceId, actorId, query, topK, result, clock.instant());
    }

    @Transactional(readOnly = true)
    public SearchHistoryRepository.Page list(UUID actorId, UUID workspaceId, int page, int size) {
        knowledge.requireMember(actorId, workspaceId);
        return repository.list(workspaceId, actorId, page, size);
    }

    @Transactional(readOnly = true)
    public SearchHistoryRepository.Detail get(UUID actorId, UUID workspaceId, UUID id) {
        knowledge.requireMember(actorId, workspaceId);
        return repository.find(workspaceId, actorId, id).orElseThrow(this::notFound);
    }

    @Transactional
    public void delete(UUID actorId, UUID workspaceId, UUID id) {
        workspaces.findForUpdate(workspaceId).orElseThrow(this::notFound);
        knowledge.requireMember(actorId, workspaceId);
        if (repository.delete(workspaceId, actorId, id) == 0) throw notFound();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Search history not found.");
    }
}
