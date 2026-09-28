package ai.aegis.gateway.config;

import ai.aegis.gateway.user.Role;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Bootstraps the platform super_admin from environment variables — the pattern
 * production tools use (GitLab/Sentry/Grafana style). Idempotent: does nothing if
 * a super_admin already exists. The super_admin has no org (platform-wide scope).
 *
 * Configure via BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD. If the password
 * is blank, bootstrapping is skipped (safe default for prod until explicitly set).
 */
@Component
public class BootstrapAdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${bootstrap.admin.email:admin@aegis.local}") String adminEmail,
                                     @Value("${bootstrap.admin.password:}") String adminPassword,
                                     @Value("${bootstrap.admin.name:Platform Admin}") String adminName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole(Role.SUPER_ADMIN.toDb())) {
            return; // already bootstrapped
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("No super_admin exists and BOOTSTRAP_ADMIN_PASSWORD is unset — skipping admin bootstrap.");
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            log.warn("Bootstrap admin email {} already exists but is not super_admin — skipping.", email);
            return;
        }
        User admin = new User(UUID.randomUUID(), null, email,
                passwordEncoder.encode(adminPassword), adminName, Role.SUPER_ADMIN);
        userRepository.save(admin);
        log.info("Bootstrapped platform super_admin: {}", email);
    }
}
