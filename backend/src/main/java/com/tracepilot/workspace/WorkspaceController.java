package com.tracepilot.workspace;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tracepilot.workspace.WorkspaceDtos.AddMemberRequest;
import com.tracepilot.workspace.WorkspaceDtos.ChangeRoleRequest;
import com.tracepilot.workspace.WorkspaceDtos.CreateWorkspaceRequest;
import com.tracepilot.workspace.WorkspaceDtos.MemberResponse;
import com.tracepilot.workspace.WorkspaceDtos.WorkspaceResponse;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaceService.create(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return workspaceService.list(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workspaceService.get(UUID.fromString(jwt.getSubject()), id);
    }

    @GetMapping("/{id}/members")
    public List<MemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workspaceService.listMembers(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody AddMemberRequest request) {
        return workspaceService.addMember(UUID.fromString(jwt.getSubject()), id, request);
    }

    @PatchMapping("/{id}/members/{userId}")
    public MemberResponse changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest request) {
        return workspaceService.changeRole(UUID.fromString(jwt.getSubject()), id, userId, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
        workspaceService.removeMember(UUID.fromString(jwt.getSubject()), id, userId);
    }
}
