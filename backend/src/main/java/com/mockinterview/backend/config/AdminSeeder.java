package com.mockinterview.backend.config;

import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only convenience: promotes one already-registered user to ADMIN on startup, if
 * app.seed-admin-email is set. This is an ops/seed mechanism for local testing, not a
 * self-service "become admin" path (PLAN.md §5 deliberately has no such endpoint) — the email
 * still has to register normally first, and this only ever runs under the "dev" profile.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;

    @Value("${app.seed-admin-email:}")
    private String seedAdminEmail;

    @Override
    public void run(String... args) {
        if (seedAdminEmail == null || seedAdminEmail.isBlank()) {
            return;
        }
        userRepository.findByEmail(seedAdminEmail).ifPresentOrElse(user -> {
            if (user.getRole() != Role.ADMIN) {
                user.setRole(Role.ADMIN);
                userRepository.save(user);
                log.info("Promoted {} to ADMIN (app.seed-admin-email)", seedAdminEmail);
            }
        }, () -> log.warn("app.seed-admin-email={} but no such user is registered yet", seedAdminEmail));
    }
}
