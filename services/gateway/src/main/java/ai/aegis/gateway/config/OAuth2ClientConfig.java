package ai.aegis.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds OAuth2 client registrations only for providers whose keys are present.
 * This avoids Spring Boot's "client id must not be empty" startup failure when
 * OAuth keys are blank (the common case in dev). The whole bean is only created
 * when at least one provider is configured.
 */
@Configuration
@ConditionalOnExpression(
        "!'${GOOGLE_CLIENT_ID:}'.isEmpty() || !'${GITHUB_CLIENT_ID:}'.isEmpty() || !'${MICROSOFT_CLIENT_ID:}'.isEmpty()"
)
public class OAuth2ClientConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${GOOGLE_CLIENT_ID:}") String googleId,
            @Value("${GOOGLE_CLIENT_SECRET:}") String googleSecret,
            @Value("${GITHUB_CLIENT_ID:}") String githubId,
            @Value("${GITHUB_CLIENT_SECRET:}") String githubSecret,
            @Value("${MICROSOFT_CLIENT_ID:}") String msId,
            @Value("${MICROSOFT_CLIENT_SECRET:}") String msSecret) {

        List<ClientRegistration> registrations = new ArrayList<>();

        if (!googleId.isBlank()) {
            registrations.add(CommonOAuth2Provider.GOOGLE
                    .getBuilder("google").clientId(googleId).clientSecret(googleSecret).build());
        }
        if (!githubId.isBlank()) {
            registrations.add(CommonOAuth2Provider.GITHUB
                    .getBuilder("github").clientId(githubId).clientSecret(githubSecret).build());
        }
        if (!msId.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("microsoft")
                    .clientId(msId)
                    .clientSecret(msSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
                    .tokenUri("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                    .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
                    .userNameAttributeName("email")
                    .clientName("Microsoft")
                    .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
