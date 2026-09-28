package ai.aegis.gateway.config;

import ai.aegis.gateway.security.AuthenticationFilter;
import ai.aegis.gateway.security.OAuth2SuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT/API-key security. Public: health + auth + OAuth2 endpoints.
 * Everything else requires authentication; method-level @PreAuthorize handles RBAC.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String allowedOrigins;
    private final AuthenticationFilter authenticationFilter;
    private final OAuth2SuccessHandler oauth2SuccessHandler;

    public SecurityConfig(@Value("${app.allowed-origins:http://localhost:3000}") String allowedOrigins,
                          AuthenticationFilter authenticationFilter,
                          OAuth2SuccessHandler oauth2SuccessHandler) {
        this.allowedOrigins = allowedOrigins;
        this.authenticationFilter = authenticationFilter;
        this.oauth2SuccessHandler = oauth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> clientRegistrations) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/actuator/health/**", "/actuator/info",
                        "/api/health",
                        "/api/auth/register", "/api/auth/login",
                        "/oauth2/**", "/login/oauth2/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
            .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        // Enable OAuth2 login only when at least one provider is configured (keys present).
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth.successHandler(oauth2SuccessHandler));
        }
        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
