package com.tracepilot.notification;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final Clock clock;

    public NotificationService(NotificationRepository notificationRepository,
                               WorkspaceMemberRepository memberRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    public NotificationDtos.NotificationPage list(UUID actorId, UUID workspaceId, int page, int size) {
        requireMember(actorId, workspaceId);
        return notificationRepository.list(workspaceId, actorId, page, size);
    }

    @Transactional
    public void markRead(UUID actorId, UUID workspaceId, UUID notificationId) {
        requireMember(actorId, workspaceId);
        if (!notificationRepository.markRead(workspaceId, actorId, notificationId, clock.instant())) {
            throw notFound();
        }
    }

    public long cursor(UUID actorId, UUID workspaceId, Optional<UUID> lastEventId) {
        requireMember(actorId, workspaceId);
        return lastEventId.map(id -> notificationRepository.cursor(workspaceId, actorId, id)
            .orElseThrow(NotificationService::notFound)).orElse(0L);
    }

    public List<NotificationDtos.Delivery> after(UUID actorId, UUID workspaceId, long cursor) {
        // Recheck membership on every poll, including idle connections after a member is removed.
        requireMember(actorId, workspaceId);
        return notificationRepository.after(workspaceId, actorId, cursor);
    }

    private void requireMember(UUID actorId, UUID workspaceId) {
        memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(NotificationService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification or workspace membership not found.");
    }
}
