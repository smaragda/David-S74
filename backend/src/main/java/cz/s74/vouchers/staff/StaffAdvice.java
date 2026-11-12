package cz.s74.vouchers.staff;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class StaffAdvice {

    /** Přihlášený uživatel dostupný ve všech šablonách jako {@code user}. */
    @ModelAttribute("user")
    StaffPrincipal user(@AuthenticationPrincipal Object principal) {
        return principal instanceof StaffPrincipal p ? p : null;
    }
}
