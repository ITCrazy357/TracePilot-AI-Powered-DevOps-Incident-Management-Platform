package com.tracepilot.knowledge;

import com.tracepilot.audit.AuditAction;
import com.tracepilot.audit.AuditLog;
import java.util.Map;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.incident.IncidentRepository;
import com.tracepilot.incident.IncidentStatus;
import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMember;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import com.tracepilot.workspace.WorkspaceRepository;
import com.tracepilot.workspace.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final IncidentRepository incidentRepository;
    private final KnowledgeText text;
    private final RagProperties properties;
    private final AiProperties aiProperties;
    private final Clock clock;
    private final AuditLog audit;

    public KnowledgeService(KnowledgeRepository repository, WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository, IncidentRepository incidentRepository,
                            KnowledgeText text, RagProperties properties, AiProperties aiProperties, Clock clock, AuditLog audit) {
        this.repository = repository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.incidentRepository = incidentRepository;
        this.text = text;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.clock = clock;
        this.audit = audit;
    }

    public KnowledgeDtos.ArticlePage list(UUID actorId, UUID workspaceId, int page, int size, KnowledgeFilters filters) {
        requireMember(actorId, workspaceId);
        return repository.list(workspaceId, page, size, filters);
    }

    public KnowledgeDtos.Article get(UUID actorId, UUID workspaceId, UUID articleId) {
        requireMember(actorId, workspaceId);
        return find(workspaceId, articleId);
    }

    @Transactional
    public KnowledgeDtos.Article create(UUID actorId, UUID workspaceId, KnowledgeDtos.CreateRequest request) {
        lock(workspaceId);
        requireMember(actorId, workspaceId);
        if (request.sourceAnalysisId() != null) {
            if (request.sourceIncidentId() == null || !repository.hasSuccessfulAnalysis(
                workspaceId, request.sourceIncidentId(), request.sourceAnalysisId())) {
                throw notFound();
            }
        } else if (request.sourceIncidentId() != null) {
            var incident = incidentRepository.find(workspaceId, request.sourceIncidentId()).orElseThrow(this::notFound);
            if (incident.status() != IncidentStatus.RESOLVED) {
                throw conflict("Confirm the incident as RESOLVED before recording its fix.");
            }
        }
        var id = UUID.randomUUID();
        var clean = new KnowledgeDtos.CreateRequest(request.sourceIncidentId(), sanitize(request.title(), 160),
            sanitize(request.rootCause(), 3000), sanitize(request.resolution(), 6000), request.sourceAnalysisId());
        repository.insert(id, workspaceId, actorId, clean, clock.instant());
        audit.record(workspaceId, actorId, AuditAction.KNOWLEDGE_CREATED, id, Map.of());
        return find(workspaceId, id);
    }

    @Transactional
    public KnowledgeDtos.Article edit(UUID actorId, UUID workspaceId, UUID articleId, KnowledgeDtos.EditRequest request) {
        lock(workspaceId);
        var member = requireMember(actorId, workspaceId);
        var article = find(workspaceId, articleId);
        if (member.getRole() == WorkspaceRole.MEMBER
            && (!actorId.equals(article.createdBy()) || !"DRAFT".equals(article.status()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only managers or the draft author can edit knowledge.");
        }
        requireVersion(article, request.version());
        var clean = new KnowledgeDtos.EditRequest(request.version(), sanitize(request.title(), 160),
            sanitize(request.rootCause(), 3000), sanitize(request.resolution(), 6000));
        repository.edit(articleId, clean, clock.instant());
        audit.record(workspaceId, actorId, AuditAction.KNOWLEDGE_EDITED, articleId,
            Map.of("previousContentVersion", article.contentVersion()));
        return find(workspaceId, articleId);
    }

    @Transactional
    public KnowledgeDtos.Publication beginApproval(UUID actorId, UUID workspaceId, UUID articleId, long version) {
        lock(workspaceId);
        requireManager(actorId, workspaceId);
        requireRag();
        var article = find(workspaceId, articleId);
        requireVersion(article, version);
        if ("ARCHIVED".equals(article.status())) {
            throw conflict("Edit archived knowledge into a new draft before approval.");
        }
        if ("READY".equals(article.indexStatus())) {
            throw conflict("Knowledge is already indexed.");
        }
        if (repository.indexing(articleId, clock.instant())) {
            throw conflict("Indexing is already in progress.");
        }
        var token = UUID.randomUUID();
        repository.approve(articleId, actorId, token, clock.instant());
        audit.record(workspaceId, actorId, AuditAction.KNOWLEDGE_APPROVED, articleId,
            Map.of("contentVersion", article.contentVersion()));
        return new KnowledgeDtos.Publication(find(workspaceId, articleId), token, text.chunks(article));
    }

    @Transactional
    public KnowledgeDtos.Article publish(UUID actorId, KnowledgeDtos.Publication publication, List<float[]> vectors) {
        var article = publication.article();
        lock(article.workspaceId());
        requireManager(actorId, article.workspaceId());
        if (!repository.ownsPublication(article.id(), publication.token(), article.contentVersion())) {
            throw conflict("Knowledge changed while embeddings were generated.");
        }
        if (vectors.size() != publication.chunks().size()) {
            throw conflict("Incomplete embedding batch.");
        }
        repository.insertChunks(publication, vectors, clock.instant());
        return find(article.workspaceId(), article.id());
    }

    @Transactional
    public void publicationFailed(KnowledgeDtos.Publication publication, String code) {
        lock(publication.article().workspaceId());
        repository.failed(publication.article().id(), publication.token(), code, clock.instant());
    }

    @Transactional
    public KnowledgeDtos.Article archive(UUID actorId, UUID workspaceId, UUID articleId, long version) {
        lock(workspaceId);
        requireManager(actorId, workspaceId);
        var article = find(workspaceId, articleId);
        requireVersion(article, version);
        if (!"ARCHIVED".equals(article.status())) {
            repository.archive(articleId, clock.instant());
            audit.record(workspaceId, actorId, AuditAction.KNOWLEDGE_ARCHIVED, articleId, Map.of());
        }
        return find(workspaceId, articleId);
    }

    public void requireRag() {
        if (!properties.enabled() || !aiProperties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RAG integration is not configured.");
        }
    }

    public WorkspaceMember requireMember(UUID actorId, UUID workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(this::notFound);
    }

    private void requireManager(UUID actorId, UUID workspaceId) {
        if (requireMember(actorId, workspaceId).getRole() == WorkspaceRole.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace managers can approve or archive knowledge.");
        }
    }

    private KnowledgeDtos.Article find(UUID workspaceId, UUID articleId) {
        return repository.find(workspaceId, articleId).orElseThrow(this::notFound);
    }

    private String sanitize(String value, int maximum) {
        var clean = text.sanitize(value);
        if (clean.isBlank() || clean.length() > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Knowledge text is empty or too long after redaction.");
        }
        return clean;
    }

    private void requireVersion(KnowledgeDtos.Article article, long version) {
        if (article.version() != version) {
            throw conflict("Knowledge changed. Reload before updating.");
        }
    }

    private void lock(UUID workspaceId) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge or workspace membership not found.");
    }

    private ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
