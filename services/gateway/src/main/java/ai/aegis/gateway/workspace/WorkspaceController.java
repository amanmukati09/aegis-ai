package ai.aegis.gateway.workspace;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;

    public WorkspaceController(WorkspaceRepository workspaces, WorkspaceMemberRepository members) {
        this.workspaces = workspaces;
        this.members = members;
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

    public record MemberView(String id, String userId, String role) {
        static MemberView of(WorkspaceMember m) {
            return new MemberView(m.getId().toString(), m.getUserId().toString(), m.getRole());
        }
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
        return members.findByWorkspaceId(id).stream().map(MemberView::of).toList();
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public MemberView addMember(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                                @Valid @RequestBody AddMemberRequest req) {
        require(principal, id);
        UUID userId = UUID.fromString(req.userId());
        if (members.existsByWorkspaceIdAndUserId(id, userId)) {
            throw ApiException.conflict("User already a member");
        }
        WorkspaceMember m = new WorkspaceMember(UUID.randomUUID(), id, userId, req.role());
        members.save(m);
        return MemberView.of(m);
    }

    private Workspace require(AuthPrincipal principal, UUID id) {
        return workspaces.findByIdAndOrgId(id, principal.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }
}
