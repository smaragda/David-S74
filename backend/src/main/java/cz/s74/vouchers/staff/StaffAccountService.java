package cz.s74.vouchers.staff;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Správa účtů obsluhy (jen vedoucí). Zneplatnění účtu okamžitě ukončí jeho relace. */
@Service
public class StaffAccountService {

    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,64}$");

    private final StaffUserRepository users;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StaffAccountService(
            StaffUserRepository users,
            PasswordEncoder encoder,
            SessionRegistry sessions,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public StaffUser create(UUID actorId, String username, String displayName, StaffRole role, String password, String ip) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new BusinessRuleException(
                    "Uživatelské jméno musí mít 3–64 znaků: malá písmena bez diakritiky, číslice, tečka, pomlčka nebo podtržítko.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessRuleException("Vyplňte zobrazované jméno.");
        }
        if (role == null) {
            throw new BusinessRuleException("Vyberte roli.");
        }
        checkPassword(password);
        return tx.execute(s -> {
            if (users.findByUsernameIgnoreCase(name).isPresent()) {
                throw new BusinessRuleException("Uživatel s tímto jménem už existuje.");
            }
            StaffUser created = users.saveAndFlush(new StaffUser(
                    UUID.randomUUID(), name, displayName.trim(), encoder.encode(password), role, Instant.now(clock)));
            audit.staff(actorId, "STAFF_CREATED", null, null, ip,
                    Map.of("username", name, "role", role.name()));
            return created;
        });
    }

    public void disable(UUID actorId, UUID targetId, String ip) {
        String username = tx.execute(s -> {
            StaffUser target = users.findById(targetId).orElseThrow(() -> new BusinessRuleException("Účet neexistuje."));
            if (target.getId().equals(actorId)) {
                throw new BusinessRuleException("Nemůžete zneplatnit vlastní účet.");
            }
            if (target.isEnabled()
                    && target.getRole() == StaffRole.MANAGER
                    && users.countByRoleAndEnabledTrue(StaffRole.MANAGER) <= 1) {
                throw new BusinessRuleException("Nelze zneplatnit posledního aktivního vedoucího.");
            }
            target.disable(Instant.now(clock));
            users.save(target);
            audit.staff(actorId, "STAFF_DISABLED", null, null, ip, Map.of("username", target.getUsername()));
            return target.getUsername();
        });
        expireSessions(username);
    }

    public void enable(UUID actorId, UUID targetId, String ip) {
        tx.executeWithoutResult(s -> {
            StaffUser target = users.findById(targetId).orElseThrow(() -> new BusinessRuleException("Účet neexistuje."));
            target.enable();
            users.save(target);
            audit.staff(actorId, "STAFF_ENABLED", null, null, ip, Map.of("username", target.getUsername()));
        });
    }

    public void resetPassword(UUID actorId, UUID targetId, String newPassword, String ip) {
        checkPassword(newPassword);
        String username = tx.execute(s -> {
            StaffUser target = users.findById(targetId).orElseThrow(() -> new BusinessRuleException("Účet neexistuje."));
            target.setPasswordHash(encoder.encode(newPassword));
            users.save(target);
            audit.staff(actorId, "STAFF_PASSWORD_RESET", null, null, ip, Map.of("username", target.getUsername()));
            return target.getUsername();
        });
        expireSessions(username);
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessRuleException("Heslo musí mít alespoň " + MIN_PASSWORD_LENGTH + " znaků.");
        }
    }

    /** Ukončí všechny aktivní relace daného uživatele. */
    void expireSessions(String username) {
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails ud && ud.getUsername().equalsIgnoreCase(username)) {
                sessions.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
            }
        }
    }
}
