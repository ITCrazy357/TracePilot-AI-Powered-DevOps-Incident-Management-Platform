package com.tracepilot.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import com.tracepilot.notification.NotificationRepository;
import com.tracepilot.pipeline.OutboxRepository;
import com.tracepilot.pipeline.PipelineAccess;
import com.tracepilot.pipeline.PipelineDtos;
import com.tracepilot.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IncidentLogStore {
    private static final int MAX_SNIPPET_CHARACTERS = 32 * 1024;
    private static final int CHUNK_CHARACTERS = 4096;
    private final WorkspaceRepository workspaceRepository;
    private final PipelineAccess access;
    private final OutboxRepository outboxRepository;
    private final IncidentRepository incidentRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public IncidentLogStore(WorkspaceRepository workspaceRepository, PipelineAccess access,
                            OutboxRepository outboxRepository, IncidentRepository incidentRepository,
                            NotificationRepository notificationRepository, Clock clock) {
        this.workspaceRepository = workspaceRepository;
        this.access = access;
        this.outboxRepository = outboxRepository;
        this.incidentRepository = incidentRepository;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional
    public void save(OutboxRepository.ProcessingClaim claim, PipelineDtos.RepositoryScope fetchedScope,
                     IncidentDtos.LogSource source, String redactedLog) {
        workspaceRepository.findForUpdate(source.workspaceId()).orElseThrow();
        if (!outboxRepository.lockProcessingClaim(claim)) {
            return;
        }
        var current = access.currentScope(claim.repositoryId());
        if (current.isEmpty()) {
            outboxRepository.complete(claim, "IGNORED");
            return;
        }
        if (!current.orElseThrow().equals(fetchedScope)
            || incidentRepository.logSource(source.incidentId()).filter(source::equals).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Log source changed during capture.");
        }
        // Redaction must finish before truncating/chunking, so a secret split across chunks cannot escape detection.
        int start = Math.max(0, redactedLog.length() - MAX_SNIPPET_CHARACTERS);
        if (start > 0 && Character.isLowSurrogate(redactedLog.charAt(start))) {
            start++;
        }
        int index = 0;
        do {
            int end = Math.min(redactedLog.length(), start + CHUNK_CHARACTERS);
            if (end < redactedLog.length() && Character.isHighSurrogate(redactedLog.charAt(end - 1))) {
                end--;
            }
            var content = redactedLog.substring(start, end);
            incidentRepository.saveSnippet(source.incidentId(), source.jobId(), index++, content,
                sha256(content), clock.instant());
            start = end;
        } while (start < redactedLog.length());
        notificationRepository.notifyMembers(source.workspaceId(), source.incidentId(),
            "INCIDENT_LOGS_READY", clock.instant());
        outboxRepository.complete(claim, "SUCCEEDED");
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", exception);
        }
    }
}
