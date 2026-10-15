package com.fivegoodmins.fivegoodmins.user;

/**
 * What {@link RegistrationService#verifyEmail} made of a verification link.
 *
 * <p>Five cases rather than a boolean, because four of them want different
 * things on the screen and the fifth wants a log line. The split mirrors
 * {@link com.fivegoodmins.fivegoodmins.shared.token.TokenVerification} and
 * then adds what only the database can answer: whether the account the token
 * names is still there, and whether this call was the one that verified it.
 *
 * <p>Unlike {@link RegistrationOutcome}, none of this leaks anything. A
 * verification link is already proof that its holder received the email, so
 * telling them which of these happened reveals nothing they did not supply.
 */
public sealed interface EmailVerificationOutcome {

    /**
     * This call set {@code email_verified_at}. The account can log in from
     * now on.
     *
     * <p>The one case that must send the welcome email (ADR-005): it is sent
     * once, in the transaction that flips the column, and "has it been sent?"
     * is answered by {@code email_verified_at IS NOT NULL} rather than by a
     * column of its own. Distinguishing this from {@link AlreadyVerified} is
     * what keeps a refresh of the link from mailing it twice.
     */
    record Verified(long userId) implements EmailVerificationOutcome { }

    /**
     * The token was good and the account was already verified -- a reload, a
     * second click, or a mail client prefetching the link.
     *
     * <p>Shows the same success page as {@link Verified}. Replay is harmless
     * here, and that it is harmless is the specific reason a stateless token
     * with no revocation is acceptable for this purpose at all
     * ({@code SignedTokenService}).
     */
    record AlreadyVerified(long userId) implements EmailVerificationOutcome { }

    /**
     * This application issued the token, and it is past its seven days.
     *
     * <p>Only ever returned for a genuine token -- the signature is checked
     * before the expiry is read -- so it is safe to answer with the resend
     * form rather than an error.
     */
    record Expired() implements EmailVerificationOutcome { }

    /**
     * The signature held but the row is gone: purged after seven days
     * unverified, superseded by a re-registration over the same address, or
     * deleted (ADR-015).
     *
     * <p>Separate from {@link Expired} because the remedy differs. An expired
     * link wants "send me another one"; a link whose account no longer exists
     * wants "register again", and offering a resend form for an address with
     * no row would be an invitation to retry something that cannot work.
     */
    record NoSuchAccount() implements EmailVerificationOutcome { }

    /**
     * Not a token this application issued for this purpose: mangled in a mail
     * client, truncated by a link rewriter, or forged.
     *
     * <p>The only case worth a log line. One case, not three -- telling a
     * forger which part of their guess was wrong is free help.
     */
    record Invalid() implements EmailVerificationOutcome { }
}
