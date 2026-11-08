package cz.s74.vouchers.staff;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Přihlášený uživatel; {@link User#equals} porovnává jméno, takže funguje se {@code SessionRegistry}. */
public class StaffPrincipal extends User {

    private final UUID id;
    private final UUID tenantId;
    private final String displayName;
    private final StaffRole role;

    public StaffPrincipal(StaffUser user) {
        super(
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled(),
                true,
                true,
                true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.id = user.getId();
        this.tenantId = user.getTenantId();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
    }

    public UUID getId() {
        return id;
    }

    /** Podnik přihlášeného uživatele (null u správce platformy). */
    public UUID getTenantId() {
        return tenantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public StaffRole getRole() {
        return role;
    }

    public boolean isManager() {
        return role == StaffRole.MANAGER;
    }

    public boolean isPlatformAdmin() {
        return role == StaffRole.PLATFORM_ADMIN;
    }
}
