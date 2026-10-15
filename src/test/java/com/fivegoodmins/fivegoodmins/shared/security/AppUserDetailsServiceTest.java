package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Against a real PostgreSQL rather than a mocked repository, because two of the
 * things worth proving here are not in this class's own code: that the stored
 * address really is matched case-insensitively (the application lowercases, and
 * ck_app_user_email_lowercase is the other half of that contract), and that the
 * principal carries the database-assigned id, which an unsaved entity cannot
 * show.
 */
@Import({TestcontainersConfiguration.class, AppUserDetailsService.class})
@DataJpaTest
class AppUserDetailsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");
    private static final String PASSWORD = "correct-horse-battery-staple";

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Autowired
    AppUserRepository users;

    @Autowired
    AppUserDetailsService service;

    private AppUser unverified(String email) {
        return users.saveAndFlush(new AppUser(email, encoder.encode(PASSWORD), "Георги", NOW));
    }

    private AppUser verified(String email) {
        AppUser user = new AppUser(email, encoder.encode(PASSWORD), "Георги", NOW);
        user.markEmailVerified(NOW);
        return users.saveAndFlush(user);
    }

    @Test
    void loadsAVerifiedAccountWithItsIdAndItsRoleAsAGrantedAuthority() {
        AppUser saved = verified("verified@seed.example");

        UserDetails details = service.loadUserByUsername("verified@seed.example");

        assertThat(details).isInstanceOf(AppUserDetails.class);
        assertThat(((AppUserDetails) details).getId()).isEqualTo(saved.getId());
        assertThat(details.getUsername()).isEqualTo("verified@seed.example");
        assertThat(details.getPassword()).startsWith("{bcrypt}$2");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void matchesWhatTheUserTypedAgainstTheLowercasedStoredAddress() {
        verified("mixed.case@seed.example");

        // What a browser's autofill or a phone keyboard hands over. Without the
        // normalization in the service this is a login that fails for a reason
        // the user cannot see.
        assertThat(service.loadUserByUsername("  Mixed.Case@Seed.Example  ").getUsername())
                .isEqualTo("mixed.case@seed.example");
    }

    @Test
    void neverLeaksThePasswordHashThroughThePrincipalsToString() {
        verified("tostring@seed.example");

        UserDetails details = service.loadUserByUsername("tostring@seed.example");

        assertThat(details.toString())
                .contains("tostring@seed.example")
                .doesNotContain(details.getPassword());
    }

    @Test
    void refusesAnAccountWhoseEmailIsNotYetVerified() {
        unverified("unverified@seed.example");

        // ADR-002: registration creates an account that cannot log in until the
        // verification link is clicked.
        assertThatExceptionOfType(UsernameNotFoundException.class)
                .isThrownBy(() -> service.loadUserByUsername("unverified@seed.example"));
    }

    @Test
    void refusesABannedAccountEvenWithAVerifiedAddress() {
        AppUser banned = verified("banned@seed.example");
        banned.setEnabled(false);
        users.saveAndFlush(banned);

        assertThatExceptionOfType(UsernameNotFoundException.class)
                .isThrownBy(() -> service.loadUserByUsername("banned@seed.example"));
    }

    @Test
    void refusesAnAddressWithNoAccountAtAll() {
        assertThatExceptionOfType(UsernameNotFoundException.class)
                .isThrownBy(() -> service.loadUserByUsername("nobody@seed.example"));
    }

    /**
     * The reason {@link AppUserDetailsService} throws
     * {@link UsernameNotFoundException} for an unverified or banned account
     * instead of reporting it through {@code UserDetails.isEnabled()}.
     *
     * <p>Through {@code isEnabled()}, Spring Security's pre-authentication
     * checks would raise a {@code DisabledException} -- a different exception
     * with a different message -- and would raise it <em>before</em> comparing
     * the password. Submitting any password at all would then tell an attacker
     * which addresses have accounts on an activism platform. This test is the
     * guard on that: all three failure modes must be indistinguishable from
     * outside, in type and in message.
     */
    @Test
    void failsIdenticallyWhetherTheAddressIsUnknownUnverifiedOrJustTheWrongPassword() {
        unverified("oracle-unverified@seed.example");
        verified("oracle-verified@seed.example");

        AuthenticationProvider provider = provider();

        String unknownAddress = failureFrom(provider, "oracle-nobody@seed.example", PASSWORD);
        String unverifiedAccount = failureFrom(provider, "oracle-unverified@seed.example", PASSWORD);
        String wrongPassword = failureFrom(provider, "oracle-verified@seed.example", "not-the-password");

        assertThat(unknownAddress).startsWith(BadCredentialsException.class.getName());
        assertThat(unverifiedAccount).isEqualTo(unknownAddress);
        assertThat(wrongPassword).isEqualTo(unknownAddress);
    }

    /**
     * The same provider Spring Security's form login builds, so the test
     * exercises the real translation: hideUserNotFoundExceptions defaults to
     * true, which is what turns a UsernameNotFoundException into the generic
     * BadCredentialsException. The provider also verifies a dummy hash when no
     * user is found, so the three paths above cost comparable time and the
     * oracle does not come back as a measurable delay.
     */
    private AuthenticationProvider provider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(service);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    /** Exception type and message as one string, since both must match. */
    private static String failureFrom(AuthenticationProvider provider, String email, String password) {
        try {
            provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            throw new AssertionError("authentication unexpectedly succeeded for " + email);
        } catch (AuthenticationException expected) {
            return expected.getClass().getName() + ": " + expected.getMessage();
        }
    }
}
