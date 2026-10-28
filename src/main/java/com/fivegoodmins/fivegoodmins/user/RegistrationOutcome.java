package com.fivegoodmins.fivegoodmins.user;

/**
 * What {@link RegistrationService#register} did with a submitted address.
 *
 * <p><strong>Both cases render the same page.</strong> That is the point of
 * the type: the caller has to know whether to send a verification email, and
 * it must not let that knowledge reach the response. A registration form that
 * says "this address is already registered" is the account enumeration oracle
 * {@link com.fivegoodmins.fivegoodmins.shared.security.AppUserDetailsService}
 * goes to some trouble to keep the login form from being, and leaking it here
 * instead would make that work pointless. For a site whose users are
 * activists, "does this person have an account here" is not a harmless fact.
 *
 * <p>Sealed, so a {@code switch} at the call site is exhaustive without a
 * default branch -- a third case would then be a compile error at every
 * caller rather than silently falling into whichever branch happened to be
 * last.
 */
public sealed interface RegistrationOutcome {

    /**
     * A row now exists for this address and nobody has proved they can read
     * the mailbox yet.
     *
     * <p>No verification token. It was here while there was no email transport
     * and the caller had to be handed the link; now the token goes straight
     * into {@code RegistrationEmailEvent.Verification} and never comes back out
     * through the return value. That is not tidying: a token on this record is
     * a token a controller can put in a flash message, a log line or a redirect
     * -- and anyone holding it can confirm this account.
     *
     * @param userId app_user.id of the new row. New even when the address was
     *               taken by an unverified row, because {@link
     *               RegistrationService#register} replaces that row rather than
     *               updating it.
     */
    record Registered(long userId) implements RegistrationOutcome { }

    /**
     * The address already belongs to an account whose owner has proved they
     * can read the mailbox. Nothing was written and no token was issued.
     *
     * <p>Carries no user id, deliberately: there is nothing a caller could
     * legitimately do with the id of an account the person at the keyboard has
     * not shown they own.
     *
     * <p>The person who genuinely forgot they have an account is answered by
     * email rather than by the page: {@code
     * RegistrationEmailEvent.ExistingAccount} tells the owner of the mailbox
     * what the form will not tell a stranger. It offers the login page, not a
     * password reset, because there is no reset until v1.1.
     *
     * <p>It goes out through <strong>the same after-commit, asynchronous
     * listener as the verification email</strong> (ADR-005), and for a reason
     * beyond tidiness: this outcome's whole job is to be indistinguishable from
     * {@link Registered}, and a sender called on one path and not the other is
     * a difference measurable with a stopwatch. Two paths that both publish an
     * event and return are the same shape as well as the same page.
     */
    record AddressTaken() implements RegistrationOutcome { }
}
