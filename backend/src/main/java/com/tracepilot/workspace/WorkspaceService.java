package com.tracepilot.workspace;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.tracepilot.identity.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.tracepilot.workspace.WorkspaceDtos.AddMemberRequest;
import com.tracepilot.workspace.WorkspaceDtos.ChangeRoleRequest;
import com.tracepilot.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.tracepilot.workspace.WorkspaceDtos.MemberResponse;
import com.tracepilot.workspace.WorkspaceDtos.WorkspaceResponse;

import static com.tracepilot.workspace.WorkspaceRole.OWNER;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceMemberRepository memberRepository,
                            UserRepository userRepository, Clock clock) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public WorkspaceResponse create(UUID actorId, CreateWorkspaceRequest request) {
        if (!userRepository.existsById(actorId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var now = clock.instant();
        var newWorkspace = new Workspace(request.name().strip(), request.slug(), actorId, now);
        var workspace = workspaceRepository.saveAndFlush(newWorkspace);
        memberRepository.save(new WorkspaceMember(workspace.getId(), actorId, OWNER, now));
        return WorkspaceResponse.from(workspace);
    }

    public List<WorkspaceResponse> list(UUID actorId) {
        return workspaceRepository.findAccessible(actorId).stream().map(WorkspaceResponse::from).toList();
    }

    public WorkspaceResponse get(UUID actorId, UUID workspaceId) {
        requireMembership(workspaceId, actorId);
        var workspace = workspaceRepository.findById(workspaceId).orElseThrow(WorkspaceService::notFound);
        return WorkspaceResponse.from(workspace);
    }

    public List<MemberResponse> listMembers(UUID actorId, UUID workspaceId) {
        requireMembership(workspaceId, actorId);
        return memberRepository.findByWorkspaceIdAndStatusOrderByJoinedAtAsc(workspaceId, MembershipStatus.ACTIVE)
            .stream().map(MemberResponse::from).toList();
    }

    @Transactional
    public MemberResponse addMember(UUID actorId, UUID workspaceId, AddMemberRequest request) {
        lockWorkspace(workspaceId);
        var actorMembership = requireMembership(workspaceId, actorId);
        requireCanManageRole(actorMembership.getRole(), request.role());
        if (!userRepository.existsById(request.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        var existing = memberRepository.findByWorkspaceIdAndUserId(workspaceId, request.userId());
        if (existing.isEmpty()) {
            var member = new WorkspaceMember(workspaceId, request.userId(), request.role(), clock.instant());
            return MemberResponse.from(memberRepository.save(member));
        }
        var member = existing.orElseThrow();
        if (member.getStatus() == MembershipStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member.");
        }
        member.reactivate(request.role(), clock.instant());
        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse changeRole(UUID actorId, UUID workspaceId, UUID targetUserId, ChangeRoleRequest request) {
        lockWorkspace(workspaceId);
        var actorMembership = requireMembership(workspaceId, actorId);
        var member = requireMembership(workspaceId, targetUserId);
        requireCanManageRole(actorMembership.getRole(), member.getRole());
        requireCanManageRole(actorMembership.getRole(), request.role());
        member.changeRole(request.role());
        return MemberResponse.from(member);
    }

    @Transactional
    public void removeMember(UUID actorId, UUID workspaceId, UUID targetUserId) {
        lockWorkspace(workspaceId);
        var actorMembership = requireMembership(workspaceId, actorId);
        var member = requireMembership(workspaceId, targetUserId);
        requireCanManageRole(actorMembership.getRole(), member.getRole());
        member.remove();
    }

    private WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
        return memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, userId, MembershipStatus.ACTIVE)
            .orElseThrow(WorkspaceService::notFound);
    }

    private void lockWorkspace(UUID workspaceId) {
        workspaceRepository.findForUpdate(workspaceId).orElseThrow(WorkspaceService::notFound);
    }

    private static void requireCanManageRole(WorkspaceRole actorRole, WorkspaceRole managedRole) {
        boolean allowed = switch (actorRole) {
            case OWNER -> managedRole != WorkspaceRole.OWNER;
            case ADMIN -> managedRole == WorkspaceRole.MEMBER;
            case MEMBER -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient workspace permissions.");
        }
    }

    private static ResponseStatusException notFound() {
        // Do not reveal whether an inaccessible workspace or membership exists.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace or membership not found.");
    }
}
