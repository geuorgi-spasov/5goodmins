package com.fivegoodmins.fivegoodmins.shared.token;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Spring and no database: this class is a key, a clock and some string
 * handling, and all three are constructor arguments.
 *
 * <p>What is worth proving here is not that a round trip works -- it is that
 * every way of arriving at a token without the key fails. Each test below is a
 * thing an attacker or a bad link would actually do: extend the expiry, change
 * whose account it names, carry a verification token to the unsubscribe
 * endpoint, or present one signed with a key from somewhere else.
 */
class SignedTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");
    private static final String SECRET = "a-test-signing-key-of-at-least-32-chars";
    private static final long USER_ID = 42L;

    private final TickingClock clock = new TickingClock(NOW);
    private final SignedTokenService service = serviceWith(SECRET, clock);

    private static SignedTokenService serviceWith(String secret, Clock clock) {
        return new SignedTokenService(new SignedTokenProperties(secret), clock);
    }

    /** Rebuilds a token with one field replaced, keeping the original MAC. */
    private static String withField(String token, int index, String replacement) {
        String[] fields = token.split("\\.", -1);
        fields[index] = replacement;
        return String.join(".", fields);
    }

    @Test
    void issuesATokenThatVerifiesBackToTheUserItNames() {
        String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

        assertThat(service.verify(token, TokenPurpose.EMAIL_VERIFICATION))
                .isEqualTo(new TokenVerification.Valid(USER_ID));
    }

    @Test
    void producesAUrlSafeTokenOfThreeFields() {
        String token = service.issue(USER_ID, TokenPurpose.UNSUBSCRIBE);

        // The whole point of Base64url without padding: this goes into a link
        // in an HTML email, and +, / and = would each need escaping somewhere
        // along the way.
        assertThat(token).matches("[0-9]+\\.[0-9]+\\.[A-Za-z0-9_-]+");
        assertThat(token.split("\\.", -1)).hasSize(3);
        assertThat(token).startsWith(USER_ID + ".");
    }

    @Test
    void signsTheExpiryItPromises() {
        String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

        long expiry = Long.parseLong(token.split("\\.", -1)[1]);
        assertThat(Instant.ofEpochSecond(expiry)).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Nested
    class Expiry {

        @Test
        void acceptsATokenASecondBeforeItExpires() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            clock.set(NOW.plus(Duration.ofDays(7)).minusSeconds(1));

            assertThat(service.verify(token, TokenPurpose.EMAIL_VERIFICATION))
                    .isEqualTo(new TokenVerification.Valid(USER_ID));
        }

        /**
         * The boundary is exclusive: at the stamped second the token is gone.
         * Asserted because "expires at T" has two readings and only one of them
         * is in the code.
         */
        @Test
        void rejectsATokenAtTheExactSecondItExpires() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            clock.set(NOW.plus(Duration.ofDays(7)));

            assertThat(service.verify(token, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Expired.class);
        }

        /**
         * Expired, not Invalid. The distinction is the whole reason
         * TokenVerification has three cases: this user gets the resend form
         * (ADR-002), and a token that fails its signature does not.
         */
        @Test
        void reportsAnAgedOutTokenAsExpiredRatherThanInvalid()  {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            clock.set(NOW.plus(Duration.ofDays(30)));

            assertThat(service.verify(token, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Expired.class);
        }

        /**
         * An unsubscribe link found in a two-year-old digest must still work --
         * EU law, not a nicety (ADR-005).
         */
        @Test
        void keepsAnUnsubscribeTokenValidForYears() {
            String token = service.issue(USER_ID, TokenPurpose.UNSUBSCRIBE);

            clock.set(NOW.plus(Duration.ofDays(2 * 365)));

            assertThat(service.verify(token, TokenPurpose.UNSUBSCRIBE))
                    .isEqualTo(new TokenVerification.Valid(USER_ID));
        }

        /**
         * The attack the expiry exists to stop, and the one a token without a
         * MAC over it would allow: the holder rewrites the date.
         */
        @Test
        void refusesATokenWhoseExpiryHasBeenExtended() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);
            long farFuture = NOW.plus(Duration.ofDays(3650)).getEpochSecond();

            String extended = withField(token, 1, Long.toString(farFuture));

            assertThat(service.verify(extended, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }
    }

    @Nested
    class Forgery {

        /**
         * One key signs every link this application emails, so this is the
         * check that keeps that from meaning one link opens every door: an
         * unsubscribe token handed to the verification endpoint is not a
         * verification token, and the reverse.
         */
        @Test
        void refusesATokenIssuedForADifferentPurpose() {
            String unsubscribe = service.issue(USER_ID, TokenPurpose.UNSUBSCRIBE);

            assertThat(service.verify(unsubscribe, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
            assertThat(service.verify(unsubscribe, TokenPurpose.UNSUBSCRIBE))
                    .isEqualTo(new TokenVerification.Valid(USER_ID));
        }

        /** Verifying somebody else's address, or unsubscribing them. */
        @Test
        void refusesATokenRepointedAtAnotherUser() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            String repointed = withField(token, 0, Long.toString(USER_ID + 1));

            assertThat(service.verify(repointed, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }

        @Test
        void refusesATokenSignedWithAnotherKey() {
            SignedTokenService other = serviceWith("a-different-key-of-at-least-32-characters", clock);

            String foreign = other.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            assertThat(service.verify(foreign, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }

        /**
         * Rotating the key invalidates every outstanding link. Asserted rather
         * than assumed, because it is the documented cost of rotation in
         * SignedTokenProperties and someone will want to know whether it is
         * really true before changing the environment variable.
         */
        @Test
        void stopsHonouringItsOwnTokensOnceTheKeyChanges() {
            String issuedBefore = service.issue(USER_ID, TokenPurpose.UNSUBSCRIBE);

            SignedTokenService rotated = serviceWith("the-rotated-key-of-at-least-32-characters", clock);

            assertThat(rotated.verify(issuedBefore, TokenPurpose.UNSUBSCRIBE))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }

        @Test
        void refusesATokenWithTheMacStripped() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);

            assertThat(service.verify(withField(token, 2, ""), TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }

        /**
         * A MAC of the right alphabet and the wrong length. MessageDigest.isEqual
         * returns early on a length mismatch, which is the one shortcut it is
         * allowed; this asserts it still says no.
         */
        @Test
        void refusesATokenWithAShortenedMac() {
            String token = service.issue(USER_ID, TokenPurpose.EMAIL_VERIFICATION);
            String mac = token.split("\\.", -1)[2];

            assertThat(service.verify(withField(token, 2, mac.substring(0, 8)), TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }
    }

    @Nested
    class Malformed {

        /**
         * Everything a mangled link, a truncating email client or a probe can
         * present. None of these may throw: verify() is called with whatever
         * arrives in the query string, and an exception here is a 500 on a
         * public endpoint.
         */
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {
                "",
                ".",
                "..",
                "42",
                "42.1760000000",
                "42.1760000000.mac.extra",
                "42.1760000000.",
                ".1760000000.mac",
                "not-a-number.1760000000.bWFj",
                "42.not-a-number.bWFj",
                "42.1760000000.!!!not-base64!!!",
                "42.1760000000.bWFj+/=",
        })
        void areInvalidAndNeverThrow(String token) {
            assertThat(service.verify(token, TokenPurpose.EMAIL_VERIFICATION))
                    .isInstanceOf(TokenVerification.Invalid.class);
        }
    }

    /**
     * A clock that can be moved, which is the whole reason the service takes one
     * (CLAUDE.md). The alternative for the expiry tests is Thread.sleep, and a
     * seven-day expiry does not have a version of that worth writing.
     */
    private static final class TickingClock extends Clock {

        private Instant now;

        private TickingClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the tests have no business converting zones");
        }
    }
}
