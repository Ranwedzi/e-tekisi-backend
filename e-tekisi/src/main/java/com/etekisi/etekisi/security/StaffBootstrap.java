package com.etekisi.etekisi.security;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.StaffUser;
import com.etekisi.etekisi.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first operator/admin account from environment variables, since
 * there is no public sign-up for staff. Does nothing if
 * {@code ADMIN_BOOTSTRAP_EMAIL} is unset or the account already exists (the
 * password is never overwritten on restart).
 */
@Component
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final StaffUserRepository staff;
    private final BCryptPasswordEncoder encoder;
    private final String email;
    private final String password;
    private final String name;
    private final String role;

    public StaffBootstrap(StaffUserRepository staff,
                          BCryptPasswordEncoder encoder,
                          @Value("${tekisi.admin.bootstrap-email:}") String email,
                          @Value("${tekisi.admin.bootstrap-password:}") String password,
                          @Value("${tekisi.admin.bootstrap-name:Operator}") String name,
                          @Value("${tekisi.admin.bootstrap-role:OPERATOR}") String role) {
        this.staff = staff;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = role;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) return;
        if (staff.findByEmailIgnoreCase(email.trim()).isPresent()) return;
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            log.warn("staff.bootstrap.skipped reason=password must be at least {} characters", MIN_PASSWORD_LENGTH);
            return;
        }
        Role r = Role.valueOf(role.trim().toUpperCase());
        if (!r.isStaff()) {
            log.warn("staff.bootstrap.skipped reason=role must be OPERATOR or ADMIN, got {}", role);
            return;
        }
        StaffUser u = new StaffUser();
        u.setEmail(email.trim().toLowerCase());
        u.setFullName(name);
        u.setPassword(encoder.encode(password));
        u.setRole(r);
        try {
            staff.save(u);
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyThere) {
            // Several instances starting together: another one created it first.
            log.info("staff.bootstrap.exists email={} (created by another instance)", u.getEmail());
            return;
        }
        log.info("staff.bootstrap.created email={} role={}", u.getEmail(), r);
    }
}
