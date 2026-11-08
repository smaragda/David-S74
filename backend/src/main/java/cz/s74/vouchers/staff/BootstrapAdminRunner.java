package cz.s74.vouchers.staff;

import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.tenant.Tenants;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Založí prvního vedoucího výchozího podniku (S74) a prvního správce platformy z proměnných prostředí,
 * pokud ještě žádný aktivní neexistuje.
 */
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
        if (cfg != null && cfg.configured()
                && !users.existsByTenantIdAndRoleAndEnabledTrue(Tenants.DEFAULT_ID, StaffRole.MANAGER)) {
            create(cfg, Tenants.DEFAULT_ID, StaffRole.MANAGER, "S74_BOOTSTRAP_ADMIN");
        }
        S74Properties.BootstrapAdmin platform = props.bootstrapPlatformAdmin();
        if (platform != null && platform.configured() && !users.existsByRoleAndEnabledTrue(StaffRole.PLATFORM_ADMIN)) {
            create(platform, null, StaffRole.PLATFORM_ADMIN, "S74_BOOTSTRAP_PLATFORM_ADMIN");
        }
    }

    private void create(S74Properties.BootstrapAdmin cfg, UUID tenantId, StaffRole role, String envPrefix) {
        if (cfg.password().length() < StaffAccountService.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    envPrefix + "_PASSWORD must have at least " + StaffAccountService.MIN_PASSWORD_LENGTH + " characters");
        }
        String username = cfg.username().trim().toLowerCase();
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            throw new IllegalStateException(envPrefix + "_USERNAME '" + username + "' is already taken by another account");
        }
        users.save(new StaffUser(
                UUID.randomUUID(),
                tenantId,
                username,
                cfg.username().trim(),
                encoder.encode(cfg.password()),
                role,
                Instant.now(clock)));
        log.warn("Bootstrap {} account '{}' created. Remove {}_* from the environment now.", role, username, envPrefix);
    }
}
