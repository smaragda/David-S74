package cz.s74.vouchers.staff;

import cz.s74.vouchers.config.S74Properties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Založí prvního vedoucího z proměnných prostředí, pokud ještě žádný aktivní neexistuje. */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final S74Properties props;
    private final StaffUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public BootstrapAdminRunner(S74Properties props, StaffUserRepository users, PasswordEncoder encoder, Clock clock) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        S74Properties.BootstrapAdmin cfg = props.bootstrapAdmin();
        if (cfg == null || !cfg.configured()) {
            return;
        }
        if (users.existsByRoleAndEnabledTrue(StaffRole.MANAGER)) {
            return; // vedoucí už existuje – bootstrap proměnné se ignorují
        }
        if (cfg.password().length() < StaffAccountService.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "S74_BOOTSTRAP_ADMIN_PASSWORD must have at least " + StaffAccountService.MIN_PASSWORD_LENGTH + " characters");
        }
        users.save(new StaffUser(
                UUID.randomUUID(),
                cfg.username().trim().toLowerCase(),
                cfg.username().trim(),
                encoder.encode(cfg.password()),
                StaffRole.MANAGER,
                Instant.now(clock)));
        log.warn("Bootstrap manager account '{}' created. Remove S74_BOOTSTRAP_ADMIN_* from the environment now.",
                cfg.username().trim());
    }
}
