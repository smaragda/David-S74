package cz.s74.vouchers.staff;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository users;

    public StaffUserDetailsService(StaffUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsernameIgnoreCase(username.trim())
                .map(StaffPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
