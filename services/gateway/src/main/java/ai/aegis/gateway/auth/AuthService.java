package ai.aegis.gateway.auth;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.auth.dto.AuthDtos.AuthResponse;
import ai.aegis.gateway.auth.dto.AuthDtos.LoginRequest;
import ai.aegis.gateway.auth.dto.AuthDtos.RegisterRequest;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.JwtService;
import ai.aegis.gateway.tenant.Organization;
import ai.aegis.gateway.tenant.OrganizationRepository;
import ai.aegis.gateway.user.Role;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Auth flows. Industry-standard B2B onboarding: self-registration creates a new
 * organization and makes the registrant its org_admin. Login verifies BCrypt and
 * issues a JWT.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, OrganizationRepository orgRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditService auditService) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req, String ip) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Email already registered");
        }

        Organization org = new Organization(UUID.randomUUID(), req.organizationName().trim(),
                uniqueSlug(req.organizationName()));
        orgRepository.save(org);

        User user = new User(UUID.randomUUID(), org.getId(), email,
                passwordEncoder.encode(req.password()), req.fullName().trim(), Role.ORG_ADMIN);
        userRepository.save(user);

        auditService.record(org.getId(), user.getId(), email, "register", "user", user.getId().toString(), ip);
        return toAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req, String ip) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("Incorrect email or password"));
        if (!user.isActive() || user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Incorrect email or password");
        }
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);
        auditService.record(user.getOrgId(), user.getId(), email, "login", "user", user.getId().toString(), ip);
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.issue(user.getId(), user.getOrgId(), user.getEmail(), user.getRole().toDb());
        return new AuthResponse(
                token, "bearer",
                user.getId().toString(),
                user.getOrgId() == null ? null : user.getOrgId().toString(),
                user.getEmail(), user.getFullName(), user.getRole().toDb()
        );
    }

    private String uniqueSlug(String name) {
        String base = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "org";
        }
        String slug = base;
        int i = 1;
        while (orgRepository.existsBySlug(slug)) {
            slug = base + "-" + (++i);
        }
        return slug;
    }
}
