package com.fivegoodmins.fivegoodmins.user;

/**
 * What {@link RegistrationService#verifyEmail} made of a verification link.
 *
 * <p>Four cases. An earlier draft had five -- expired and
 * account-no-longer-exists were separate, on the reasoning that an expired
 * link wants a "resend the verification email" form while a missing account
 * wants "register again". That reasoning does not survive the fact that the
 * token's lifetime and the purge window are deliberately the same seven days
 * (ADR-002), which makes the two states nearly the same state and the resend
 * form actively wrong:
 *
 * <ul>
 *   <li>By the time a link expires, the row it names is already past the purge
 *       cutoff. It survives only until the next nightly run, so the window in
 *       which "expired" and "still there" overlap is hours, not days.</li>
 *   <li>Worse, a resend inside that window would issue a fresh seven-day token
 *       against a row that 03:30 is about to delete. The user would receive a
 *       second link that dies overnight -- and the fix for <em>that</em> would
 *       be to push the row's created_at forward on every resend, which is a
 *       way to keep a squatted address alive indefinitely.</li>
 * </ul>
 *
 * <p>So both collapse into {@link DeadLink}, whose answer is "register again"
 * -- and registering again over an unverified row already <em>is</em> a
 * resend, because {@link RegistrationService#register} replaces the row and
 * issues a new token. The feature was deleted rather than built. (The
 * resend-verification form ADR-002 asks for still has a job: the user who
 * registered an hour ago and never received the first email. That case is an
 * unexpired token, and it never reaches this type.)
 *
 * <p>Nothing here leaks. A verification link is already proof that its holder
 * received the email, so saying which of these happened reveals nothing they
 * did not supply -- unlike {@link RegistrationOutcome}, where the whole point
 * of the type is that the caller must not say.
 */
public sealed interface EmailVerificationOutcome {

    /**
     * This call set {@code email_verified_at}. The account can log in from
     * now on.
     *
     * <p>The one case that must send the welcome email (ADR-005): once, in the
     * transaction that flips the column, with "has it been sent?" answered by
     * {@code email_verified_at IS NOT NULL} rather than by a column of its
     * own. Distinguishing this from {@link AlreadyVerified} is what keeps a
     * reload from mailing it twice.
     */
    record Verified(long userId) implements EmailVerificationOutcome { }

    /**
     * The token was authentic and the account is already confirmed -- a
     * reload, a second click, or a mail client prefetching the link.
     *
     * <p><strong>Returned for an expired token too</strong>, which is the
     * reason {@code TokenVerification.Expired} carries a user id. Someone
     * who confirmed on day one and clicks the same link again on day nine
     * holds a token that is past its expiry but names a perfectly good
     * account; telling them to register again would send them round a loop
     * that ends at "check your inbox" for an email that is never sent,
     * because {@link RegistrationOutcome.AddressTaken} declines to touch a
     * verified row. "You are already confirmed, just log in" is both true and
     * the end of the conversation.
     *
     * <p>Shows the same page as {@link Verified}. Replay being harmless is
     * the specific reason a stateless token with no revocation is acceptable
     * for this purpose at all ({@code SignedTokenService}).
     */
    record AlreadyVerified(long userId) implements EmailVerificationOutcome { }

    /**
     * The signature held, but the link can no longer confirm anything: it
     * aged out, or the row it names is gone -- purged after seven days
     * unverified, superseded by a re-registration over the same address, or
     * deleted (ADR-015).
     *
     * <p>One case rather than two because the remedy is one remedy. See the
     * type's own Javadoc for why "resend" is not it.
     *
     * <p>Carries no user id: there is nothing left to act on, and in the
     * purged case there is no row for an id to mean anything about.
     */
    record DeadLink() implements EmailVerificationOutcome { }

    /**
     * Not a token this application issued for this purpose: mangled in a mail
     * client, truncated by a link rewriter, carried over from an unsubscribe
     * link, or forged.
     *
     * <p>The only case worth a log line, and deliberately one case -- telling
     * a forger which part of their guess was wrong is free help.
     */
    record Invalid() implements EmailVerificationOutcome { }
}
