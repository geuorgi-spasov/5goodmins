package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Loads the login identity from app_user.
 *
 * <p><b>The one design decision in this class: every reason not to log in
 * produces the same exception.</b> Unknown address, wrong password, unverified
 * email, banned account -- all four leave through the same
 * {@link UsernameNotFoundException}, which {@code DaoAuthenticationProvider}
 * converts to a {@code BadCredentialsException} carrying the one generic
 * message, because {@code hideUserNotFoundExceptions} defaults to true.
 *
 * <p>The obvious implementation does not do this. Mapping
 * {@code email_verified_at IS NULL} onto {@code UserDetails.isEnabled() ==
 * false} and letting Spring Security block the login is the shape every tutorial
 * has, and it is an account enumeration oracle:
 * {@code AbstractUserDetailsAuthenticationProvider} runs its pre-authentication
 * checks <em>before</em> comparing the password, so a {@code DisabledException}
 * -- a different exception, a different message on the login form -- comes back
 * for an address that exists and is unverified while any password at all is
 * submitted. Try a list of addresses against the form and the ones that answer
 * differently are the ones with accounts here. For a site whose users are
 * activists, "does this person have an account on an activism platform" is not a
 * harmless fact to leak.
 *
 * <p>Hence the gate lives here, above Spring Security's status checks, and it
 * throws the exception the provider is willing to hide. The provider also
 * verifies a dummy hash when no user is found, so the two paths cost roughly the
 * same wall-clock time and the oracle does not reappear as a timing difference.
 *
 * <p>What this costs: a user who has not clicked the verification link is told
 * "wrong credentials", which is unhelpful and true-ish. The answer is not a more
 * specific error, it is the registration flow -- a "resend the verification
 * email" form that accepts any address and always answers the same way, so it
 * tells the owner of the mailbox what it will not tell a stranger. That form is
 * part of the next task.
 */
@Service
class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    // No @Transactional: one repository call, which already runs in its own
    // transaction, and nothing lazy is touched afterwards -- AppUserDetails
    // copies the four scalar fields it needs while the row is still at hand.
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findByEmail(normalize(username))
                // The messages below are for the application log, which is the
                // only place they go: the provider replaces them before the
                // user ever sees one. They are the only record of *which* of
                // the two reasons applied, so they say.
                .orElseThrow(() -> new UsernameNotFoundException("no account for the submitted address"));

        if (!user.isEnabled() || !user.isEmailVerified()) {
            throw new UsernameNotFoundException(
                    "account " + user.getId() + " cannot log in: enabled=" + user.isEnabled()
                            + ", emailVerified=" + user.isEmailVerified());
        }

        return new AppUserDetails(user);
    }

    /**
     * AppUserRepository.findByEmail matches on equality, and
     * ck_app_user_email_lowercase guarantees every stored address is already
     * lowercase -- so the submitted one has to be lowercased here or the
     * database's half of that contract does nothing and "Georgi@Example.com"
     * fails to log in.
     *
     * <p>{@link Locale#ROOT}, not the default locale: under a Turkish locale
     * {@code "I".toLowerCase()} is "ı", which would turn an address
     * containing I into one that matches no row -- a login that works or not
     * depending on the server's locale.
     */
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
