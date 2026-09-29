package ai.aegis.gateway.workspace;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final UserRepository users;

    public WorkspaceController(WorkspaceRepository workspaces, WorkspaceMemberRepository members,
                               UserRepository users) {
        this.workspaces = workspaces;
        this.members = members;
        this.users = users;
    }

    public record CreateRequest(@NotBlank String name, String description) {
    }

    public record AddMemberRequest(@NotBlank String userId, String role) {
    }

    public record WorkspaceView(String id, String name, String description, OffsetDateTime createdAt) {
        static WorkspaceView of(Workspace w) {
            return new WorkspaceView(w.getId().toString(), w.getName(), w.getDescription(), w.getCreatedAt());
        }
    }

    /** Member view enriched with email/name so the frontend never has to show a raw UUID. */
    public record MemberView(String id, String userId, String email, String fullName, String role) {
    }

    /** A candidate the caller can add as a workspace member — org users not yet in it. */
    public record OrgUserView(String id, String email, String fullName) {
    }

    @GetMapping
    public List<WorkspaceView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return workspaces.findByOrgIdOrderByCreatedAtDesc(principal.orgId())
                .stream().map(WorkspaceView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public WorkspaceView create(@AuthenticationPrincipal AuthPrincipal principal,
                                @Valid @RequestBody CreateRequest req) {
        Workspace w = new Workspace(UUID.randomUUID(), principal.orgId(), req.name(),
                req.description(), principal.userId());
        workspaces.save(w);
        // Creator is automatically a member/admin of the workspace.
        members.save(new WorkspaceMember(UUID.randomUUID(), w.getId(), principal.userId(), "admin"));
        return WorkspaceView.of(w);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        Workspace w = require(principal, id);
        members.deleteByWorkspaceId(w.getId());
        workspaces.delete(w);
    }

    @GetMapping("/{id}/members")
    public List<MemberView> listMembers(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        require(principal, id);
        List<WorkspaceMember> rows = members.findByWorkspaceId(id);
        Map<UUID, User> byId = users.findAllById(rows.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return rows.stream().map(m -> {
            User u = byId.get(m.getUserId());
            return new MemberView(m.getId().toString(), m.getUserId().toString(),
                    u == null ? "(deleted user)" : u.getEmail(),
                    u == null ? "" : u.getFullName(), m.getRole());
        }).toList();
    }

    /**
     * Org users not yet in this workspace — powers a proper picker on the frontend
     * instead of asking an admin to paste a raw user ID they have no way to look up.
     */
    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public List<OrgUserView> candidates(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        require(principal, id);
        var existingMemberIds = members.findByWorkspaceId(id).stream()
                .map(WorkspaceMember::getUserId).collect(java.util.stream.Collectors.toSet());
        return users.findByOrgId(principal.orgId()).stream()
                .filter(u -> !existingMemberIds.contains(u.getId()))
                .map(u -> new OrgUserView(u.getId().toString(), u.getEmail(), u.getFullName()))
                .toList();
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public MemberView addMember(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                                @Valid @RequestBody AddMemberRequest req) {
        require(principal, id);
        UUID userId = UUID.fromString(req.userId());
        User u = users.findByIdAndOrgId(userId, principal.orgId())
                .orElseThrow(() -> ApiException.badRequest("User not found in your organization"));
        if (members.existsByWorkspaceIdAndUserId(id, userId)) {
            throw ApiException.conflict("User already a member");
        }
        WorkspaceMember m = new WorkspaceMember(UUID.randomUUID(), id, userId, req.role());
        members.save(m);
        return new MemberView(m.getId().toString(), userId.toString(), u.getEmail(), u.getFullName(), m.getRole());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public void removeMember(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                             @PathVariable UUID userId) {
        require(principal, id);
        members.deleteByWorkspaceIdAndUserId(id, userId);
    }

    private Workspace require(AuthPrincipal principal, UUID id) {
        return workspaces.findByIdAndOrgId(id, principal.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }
}
