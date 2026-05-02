package com.genius.hz.admin.config;

import com.genius.hz.admin.domain.User;
import com.genius.hz.admin.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Re-hashes the placeholder bcrypts seeded by V2__seed_default_users.sql with
 * the configured bcrypt strength. This ensures the documented temp credentials
 * always work even if the SQL hashes drift, and gives a single source of truth
 * for default usernames/passwords.
 *
 * Idempotent: only re-hashes if the stored hash is the placeholder OR doesn't
 * verify. Already-rotated user passwords are NEVER touched.
 */
@Component
public class SeedUserBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(SeedUserBootstrap.class);

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<String, String>() {{
        put("superadmin", "ChangeMe!Admin#1");
        put("operator",   "ChangeMe!Op#1");
        put("developer",  "ChangeMe!Dev#1");
        put("viewer",     "ChangeMe!View#1");
    }};

    private final UserRepository  users;
    private final PasswordEncoder enc;

    public SeedUserBootstrap(UserRepository users, PasswordEncoder enc) {
        this.users = users;
        this.enc   = enc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void rehashSeedUsersIfNeeded() {
        DEFAULTS.forEach((username, plaintext) -> {
            users.findByUsername(username).ifPresent(u -> {
                boolean ok;
                try { ok = enc.matches(plaintext, u.getPasswordHash()); }
                catch (Exception e) { ok = false; }
                if (!ok && u.isMustChangePassword()) {
                    u.setPasswordHash(enc.encode(plaintext));
                    users.save(u);
                    LOG.info("Re-hashed seed temp password for user '{}'.", username);
                }
            });
        });
        LOG.info("Seed-user bootstrap complete. Default temp credentials are documented in README.md.");
    }
}
