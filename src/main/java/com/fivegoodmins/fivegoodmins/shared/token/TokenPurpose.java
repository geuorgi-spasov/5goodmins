package com.fivegoodmins.fivegoodmins.shared.token;

import java.time.Duration;

/**
 * What a signed token is for, and how long it lives.
 *
 * <p>The purpose is part of the signed material, which is the whole reason this
 * enum exists rather than a lifetime being passed in at the call site. One
 * signing key covers every link this application emails (ADR-002, ADR-005), so
 * without a purpose in the signature an unsubscribe link would verify as an
 * email-verification link for the same account: same user id, same key, same
 * format. Binding the purpose makes each token useless anywhere but the one
 * endpoint that asks for it.
 *
 * <p>The lifetime belongs here, next to the purpose, because each of these is
 * defensible only against its own use and they differ by three orders of
 * magnitude. A single configurable {@code app.token.ttl} would have to be the
 * longest of them, which is the wrong answer for all but one.
 *
 * <p><strong>Renaming a constant invalidates every token already in a mailbox.</strong>
 * {@link #name()} is the signed string, so {@code EMAIL_VERIFICATION} is a
 * wire format, not an internal label. Adding constants is free; renaming one is
 * a breaking change to links already sent.
 */
public enum TokenPurpose {

    /**
     * Confirming the address at registration.
     *
     * <p>Seven days, to match the purge of unverified rows in ADR-002: a token
     * that outlived its row would resolve to an account that no longer exists,
     * and a token that died well before the purge would leave a user holding a
     * dead link while the row it names is still there waiting. The resend form
     * issues a fresh token, so this bounds a single email, not the user's
     * window to register.
     */
    EMAIL_VERIFICATION(Duration.ofDays(7)),

    /**
     * One-click unsubscribe from the daily digest, without logging in.
     *
     * <p>Ten years, which is as close to "never" as a signed expiry gets. Every
     * digest email carries this link and the EU requires it to keep working
     * (ADR-005); a digest found in a mailbox two years later must still
     * unsubscribe, so an expiry measured in days would turn a legal obligation
     * into a dead link. The expiry stays in the signature rather than being
     * made optional because a token format with two shapes needs two
     * verification paths, and the one without an expiry is the one an attacker
     * would rather have.
     */
    UNSUBSCRIBE(Duration.ofDays(3650));

    private final Duration ttl;

    TokenPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Public because the seven days of {@link #EMAIL_VERIFICATION} is not only
     * this class's business: {@code RegistrationService} purges unverified
     * rows on the same window, and ADR-002 requires the two to be one number
     * rather than two that happen to agree. Reading it from here is what makes
     * that structural instead of a comment.
     */
    public Duration ttl() {
        return ttl;
    }
}
