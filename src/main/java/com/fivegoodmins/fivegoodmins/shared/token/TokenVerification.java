package com.fivegoodmins.fivegoodmins.shared.token;

/**
 * The outcome of {@link SignedTokenService#verify}.
 *
 * <p>Three cases rather than an {@code Optional<Long>}, because two of them
 * want different handling and the distinction is free to make. An expired
 * token is a user who waited too long and wants the resend form; a token that
 * fails its signature is either a mangled copy-paste or someone probing, and
 * is the only one of the three worth a log line. Collapsing them would mean
 * either offering a resend link to a forged token or telling a user whose link
 * simply aged out that something is wrong with it.
 *
 * <p>Sealed, so {@code switch} over it is exhaustive without a default branch
 * and adding a fourth case breaks every call site at compile time rather than
 * falling into whatever the default happened to be.
 */
public sealed interface TokenVerification {

    /**
     * The signature held and the token is in date.
     *
     * @param userId app_user.id, as signed. Authentic, but not checked for
     *               existence -- the row may have been purged (ADR-002) or the
     *               account deleted (ADR-015) since the link was sent, so the
     *               caller still has to load it and handle an absent one.
     */
    record Valid(long userId) implements TokenVerification { }

    /**
     * The signature held, so the expiry is genuine, and it has passed.
     *
     * @param userId app_user.id, as signed. Carried for the same reason
     *               {@link Valid} carries it and with the same confidence: the
     *               signature is checked before the expiry is read, so this id
     *               is authentic whether or not the token is still in date.
     *               Without it a caller cannot tell an aged-out link belonging
     *               to an account that has since been confirmed from one whose
     *               account is gone -- and those two want opposite things on
     *               the screen. Subject to the same caveat as {@link Valid}:
     *               authentic is not the same as still present.
     */
    record Expired(long userId) implements TokenVerification { }

    /**
     * Not a token this application issued under this purpose: wrong shape,
     * undecodable, or a signature that does not match. Deliberately one case --
     * telling a forger which part of their guess was wrong is free help.
     */
    record Invalid() implements TokenVerification { }
}
