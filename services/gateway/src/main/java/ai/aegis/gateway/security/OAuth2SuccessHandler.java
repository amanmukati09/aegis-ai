package ai.aegis.gateway.security;

import ai.aegis.gateway.tenant.Organization;
import ai.aegis.gateway.tenant.OrganizationRepository;
import ai.aegis.gateway.user.Role;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * On successful OAuth2 login, provision the user (creating an org + org_admin on
 * first login, like self-registration), issue an app JWT, and redirect back to the
 * frontend with the token. Only active when an OAuth provider is configured.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final JwtService jwtService;
    private final String frontendUrl;

    public OAuth2SuccessHandler(UserRepository userRepository, OrganizationRepository orgRepository,
                                JwtService jwtService,
                                @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.jwtService = jwtService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        String email = extractEmail(oauthUser);
        String name = extractName(oauthUser, email);
        String provider = authentication.getName();

        User user = userRepository.findByEmail(email).orElseGet(() -> provision(email, name, provider));
        String token = jwtService.issue(user.getId(), user.getOrgId(), user.getEmail(), user.getRole().toDb());

        response.sendRedirect(frontendUrl + "/auth/callback?token=" + token);
    }

    private User provision(String email, String name, String provider) {
        Organization org = new Organization(UUID.randomUUID(), name + "'s Organization",
                "org-" + UUID.randomUUID().toString().substring(0, 8));
        orgRepository.save(org);
        User user = new User(UUID.randomUUID(), org.getId(), email, null, name, Role.ORG_ADMIN);
        user.setAuthProvider(provider);
        return userRepository.save(user);
    }

    private String extractEmail(OAuth2User user) {
        Object email = user.getAttributes().get("email");
        if (email != null) {
            return email.toString().toLowerCase(Locale.ROOT);
        }
        // GitHub may not expose email in the profile; fall back to a login-based address.
        Object login = user.getAttributes().get("login");
        return (login != null ? login.toString() : UUID.randomUUID().toString()) + "@users.noreply.aegis.local";
    }

    private String extractName(OAuth2User user, String email) {
        Object name = user.getAttributes().get("name");
        return name != null ? name.toString() : email.split("@")[0];
    }
}
