package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenProperties;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Against a real Postgres, not Mockito, and that is the decision this class
 * is making rather than a default it fell into.
 *
 * <p>What is worth proving about registration is almost entirely database
 * behaviour. That replacing an unverified row works at all depends on
 * Hibernate ordering a DELETE before an INSERT, which it does not do without
 * the explicit flush -- against a mock repository the flush is an unverified
 * call and the constraint that would have caught its absence does not exist.
 * uq_app_user_email, ck_app_user_email_lowercase and the cutoff in the purge
 * query are in the same position. A Mockito version of this file would be
 * mocking the only thing under test.
 *
 * <p>The clock is mutable because three of the rules are about time passing
 * -- the token's seven days, the purge's seven days, and the fact that they
 * are the same seven days -- and the alternative is a test that sleeps.
 */
@Import({TestcontainersConfiguration.class,
        RegistrationServiceTest.Collaborators.class,
        SignedTokenService.class,
        RegistrationService.class})
@DataJpaTest
class RegistrationServiceTest {

    private static final String ADDRESS = "georgi@seed.example";
    private static final String PASSWORD = "a-perfectly-ordinary-password";

    @Autowired
    RegistrationService registrations;

    @Autowired
    AppUserRepository users;

    @Autowired
    SignedTokenService tokens;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetTheClock() {
        // The context is cached across methods, so the clock has to go back to
        // a baseline or the first test that advances it poisons the rest. Real
        // "now" rather than a literal, because created_at is written by
        // @CreationTimestamp from the system clock and the purge compares the
        // two: a fixed date here would make the cutoff arithmetic depend on
        // what day the suite runs.
        clock.set(Instant.now());
    }

    private RegistrationOutcome.Registered register(String email, String password, String name) {
        RegistrationOutcome outcome = registrations.register(email, password, name);
        assertThat(outcome).isInstanceOf(RegistrationOutcome.Registered.class);
        return (RegistrationOutcome.Registered) outcome;
    }

    // -----------------------------------------------------------------------
    // Registering
    // -----------------------------------------------------------------------

    @Test
    void storesTheAddressLowercasedAndTheNameTrimmed() {
        register("Georgi@Seed.Example", PASSWORD, "  Georgi  ");

        assertThat(users.findByEmail(ADDRESS))
                .get()
                .satisfies(user -> {
                    assertThat(user.getEmail()).isEqualTo(ADDRESS);
                    assertThat(user.getDisplayName()).isEqualTo("Georgi");
                });
        // The row only exists because the application normalized: the database
        // would have refused "Georgi@Seed.Example" on
        // ck_app_user_email_lowercase.
    }

    @Test
    void storesAnEncodedPasswordCarryingTheAlgorithmThatProducedIt() {
        register(ADDRESS, PASSWORD, "Georgi");

        String stored = users.findByEmail(ADDRESS).orElseThrow().getPasswordHash();

        assertThat(stored).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void createsAnAccountThatCannotLogInUntilItIsVerified() {
        register(ADDRESS, PASSWORD, "Georgi");

        AppUser user = users.findByEmail(ADDRESS).orElseThrow();

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
        // enabled is the ban switch and starts true; verification is the other
        // half of the gate, and it is this one that is still closed.
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void startsTheDigestWatermarkAtTheInjectedClockRatherThanTheSystemOne() {
        Instant frozen = Instant.parse("2026-10-15T09:00:00Z");
        clock.set(frozen);

        register(ADDRESS, PASSWORD, "Georgi");

        // Not a style point: this is what keeps the first digest from
        // containing the whole back catalogue (ADR-005), and it is only
        // assertable because the service takes a Clock.
        assertThat(users.findByEmail(ADDRESS).orElseThrow().getLastDigestSentAt())
                .isEqualTo(frozen);
    }

    // -----------------------------------------------------------------------
    // Verifying
    // -----------------------------------------------------------------------

    @Test
    void issuesATokenThatVerifiesTheAccountItNames() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        assertThat(registrations.verifyEmail(registered.verificationToken()))
                .isEqualTo(new EmailVerificationOutcome.Verified(registered.userId()));
        assertThat(users.findByEmail(ADDRESS).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void reportsASecondClickSeparatelySoTheWelcomeEmailIsSentOnce() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");
        String link = registered.verificationToken();
        Instant firstVerification = Instant.parse("2026-10-15T09:00:00Z");

        clock.set(firstVerification);
        registrations.verifyEmail(link);
        clock.advance(Duration.ofHours(1));

        // A mail client prefetching the link and the user then clicking it is
        // the ordinary case, not an edge one.
        assertThat(registrations.verifyEmail(link))
                .isEqualTo(new EmailVerificationOutcome.AlreadyVerified(registered.userId()));
        assertThat(users.findByEmail(ADDRESS).orElseThrow().getEmailVerifiedAt())
                .isEqualTo(firstVerification);
        // markEmailVerified writes once: the hour that passed is not in the row.
    }

    @Test
    void offersTheResendPathForALinkThatOnlyRanOutOfTime() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plusSeconds(1));

        // Expired, not Invalid: the signature was checked first, so this is
        // known to be a link this application really sent and it is safe to
        // answer with "we will send another one".
        assertThat(registrations.verifyEmail(registered.verificationToken()))
                .isEqualTo(new EmailVerificationOutcome.Expired());
    }

    @Test
    void refusesALinkThatWasNotIssuedForVerification() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        // The unsubscribe link in every digest email names the same user and is
        // signed with the same key. Only the purpose in the signed material
        // stops it confirming an address nobody read.
        String unsubscribe = tokens.issue(registered.userId(), TokenPurpose.UNSUBSCRIBE);

        assertThat(registrations.verifyEmail(unsubscribe))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
        assertThat(users.findByEmail(ADDRESS).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void refusesSomethingThatIsNotATokenAtAll() {
        assertThat(registrations.verifyEmail("1.9999999999.forged"))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
        assertThat(registrations.verifyEmail(null))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
    }

    @Test
    void reportsAnAuthenticLinkWhoseAccountIsGoneAsSomethingOtherThanExpired() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");
        users.deleteById(registered.userId());
        users.flush();

        // The remedy differs from Expired: there is no row to resend to, so
        // the only honest answer is "register again".
        assertThat(registrations.verifyEmail(registered.verificationToken()))
                .isEqualTo(new EmailVerificationOutcome.NoSuchAccount());
    }

    // -----------------------------------------------------------------------
    // Re-registration over an unverified row
    // -----------------------------------------------------------------------

    @Test
    void letsASecondPersonTakeOverAnAddressNobodyHasProvedTheyCanRead() {
        register(ADDRESS, "the-squatters-password", "Squatter");

        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");

        assertThat(users.count()).isOne();
        AppUser row = users.findByEmail(ADDRESS).orElseThrow();
        assertThat(row.getId()).isEqualTo(owner.userId());
        assertThat(row.getDisplayName()).isEqualTo("Georgi");
        assertThat(passwordEncoder.matches(PASSWORD, row.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("the-squatters-password", row.getPasswordHash())).isFalse();
        // That this reaches the database at all is the DELETE-before-INSERT
        // flush doing its job; without it uq_app_user_email rejects the insert.
    }

    @Test
    void killsTheSupersededLinkWhenAnAddressIsTakenOver() {
        RegistrationOutcome.Registered squatter = register(ADDRESS, "the-squatters-password", "Squatter");

        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");

        // The whole reason the row is replaced rather than updated. Had it
        // been updated in place, the id would be unchanged and the first
        // token would still verify -- so the real owner, clicking the link
        // from their own first registration, would confirm an account whose
        // password belongs to someone else.
        assertThat(squatter.userId()).isNotEqualTo(owner.userId());
        assertThat(registrations.verifyEmail(squatter.verificationToken()))
                .isEqualTo(new EmailVerificationOutcome.NoSuchAccount());
        assertThat(registrations.verifyEmail(owner.verificationToken()))
                .isEqualTo(new EmailVerificationOutcome.Verified(owner.userId()));
    }

    @Test
    void refusesToTouchAVerifiedAccountAndDeclinesToSaySo() {
        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");
        registrations.verifyEmail(owner.verificationToken());

        RegistrationOutcome outcome = registrations.register(ADDRESS, "an-attackers-password", "Not Georgi");

        // AddressTaken carries no id and no token, so there is nothing for a
        // caller to leak even by accident; the page it renders is the one
        // Registered renders.
        assertThat(outcome).isEqualTo(new RegistrationOutcome.AddressTaken());
        assertThat(users.count()).isOne();
        AppUser row = users.findByEmail(ADDRESS).orElseThrow();
        assertThat(row.getId()).isEqualTo(owner.userId());
        assertThat(row.getDisplayName()).isEqualTo("Georgi");
        assertThat(passwordEncoder.matches(PASSWORD, row.getPasswordHash())).isTrue();
    }

    // -----------------------------------------------------------------------
    // The purge
    // -----------------------------------------------------------------------

    @Test
    void leavesUnverifiedAccountsAloneForAsLongAsTheirLinkIsStillGood() {
        register(ADDRESS, PASSWORD, "Georgi");

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().minus(Duration.ofHours(1)));

        assertThat(registrations.purgeUnverifiedAccounts()).isZero();
        assertThat(users.findByEmail(ADDRESS)).isPresent();
        // One hour before the purge window is one hour before the token
        // expires, because they are the same window read from the same place.
    }

    @Test
    void removesUnverifiedAccountsOnceTheirLinkHasDiedAndKeepsVerifiedOnes() {
        register(ADDRESS, PASSWORD, "Georgi");
        RegistrationOutcome.Registered verified = register("verified@seed.example", PASSWORD, "Verified");
        registrations.verifyEmail(verified.verificationToken());

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plus(Duration.ofHours(1)));

        assertThat(registrations.purgeUnverifiedAccounts()).isOne();
        assertThat(users.findByEmail(ADDRESS)).isEmpty();
        assertThat(users.findByEmail("verified@seed.example")).isPresent();
        // The second assertion is the one that would fail if the bulk delete
        // ran before the verification reached the database -- which is what
        // flushAutomatically is for.
    }

    @Test
    void freesThePurgedAddressForWhoeverWantsItNext() {
        register(ADDRESS, "the-squatters-password", "Squatter");
        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plus(Duration.ofHours(1)));
        registrations.purgeUnverifiedAccounts();

        register(ADDRESS, PASSWORD, "Georgi");

        assertThat(users.count()).isOne();
        assertThat(passwordEncoder.matches(PASSWORD,
                users.findByEmail(ADDRESS).orElseThrow().getPasswordHash())).isTrue();
    }

    // -----------------------------------------------------------------------
    // The backstop checks
    // -----------------------------------------------------------------------

    @Test
    void refusesAPasswordThatBCryptWouldSilentlyCutShortInBulgarian() {
        // Thirty-seven Cyrillic characters: well under any character limit a
        // form would advertise, and 74 bytes in UTF-8 -- past BCrypt's 72.
        // An ASCII password of the same length is nowhere near it, which is
        // exactly why counting characters here would be a bug aimed at this
        // site's own users.
        String passphrase = "паролатамитрябвадаебезмногоотдалеченост";
        assertThat(passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(72);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, passphrase, "Georgi"))
                .withMessageContaining("72 bytes");
        assertThat(users.count()).isZero();
    }

    @Test
    void refusesInputTheSchemaWouldRejectLaterAndLessClearly() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register("  ", PASSWORD, "Georgi"))
                .withMessageContaining("email");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, PASSWORD, "   "))
                .withMessageContaining("display name");
        // Three spaces, because ck_app_user_display_name_length compares
        // btrim(display_name) and this is the value that gets past a naive
        // "not empty" check.

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, "short", "Georgi"))
                .withMessageContaining("8 characters");

        assertThat(users.count()).isZero();
    }

    // -----------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.now());
        }

        /**
         * The real signing key requirements, not a stub: the service under
         * test hands this class a user id and gets back something only this
         * key can produce, and shortening it here would quietly weaken what
         * the token tests prove elsewhere.
         */
        @Bean
        SignedTokenProperties signedTokenProperties() {
            return new SignedTokenProperties("a-test-signing-key-of-at-least-32-chars");
        }

        /**
         * The production encoder, deliberately. A no-op one would run faster
         * and would also stop these tests from noticing the day
         * {@code password_hash} is too short for the algorithm in use, which
         * is a failure the migration's comment is specifically about.
         */
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }

    /**
     * A clock the test moves by hand. {@code Clock.fixed} cannot express "and
     * then seven days pass", and {@code Thread.sleep} can only express it by
     * taking seven days.
     */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the application uses one zone: UTC");
        }
    }
}
