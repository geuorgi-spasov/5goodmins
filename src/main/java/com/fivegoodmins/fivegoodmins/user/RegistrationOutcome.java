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
     * @param userId            app_user.id of the new row. New even when the
     *                          address was taken by an unverified row, because
     *                          {@link RegistrationService#register} replaces
     *                          that row rather than updating it.
     * @param verificationToken the {@code EMAIL_VERIFICATION} token for the
     *                          link. Returned to the caller only because the
     *                          email transport does not exist yet (ADR-005);
     *                          when {@code EmailSender} lands, the service
     *                          sends the mail itself and this field goes away.
     *                          Until then: it belongs in a URL, never in a log
     *                          line, because anyone holding it can verify this
     *                          account.
     */
    record Registered(long userId, String verificationToken) implements RegistrationOutcome { }

    /**
     * The address already belongs to an account whose owner has proved they
     * can read the mailbox. Nothing was written and no token was issued.
     *
     * <p>Carries no user id, deliberately: there is nothing a caller could
     * legitimately do with the id of an account the person at the keyboard has
     * not shown they own.
     *
     * <p>The person who genuinely forgot they have an account gets silence
     * from this path. The standard answer is a "you already have an account,
     * reset your password instead" email to the address -- which tells the
     * owner of the mailbox what the form will not tell a stranger. It needs
     * the email transport, so it arrives with it.
     *
     * <p>When it does, <strong>it goes out through the same after-commit,
     * asynchronous listener as the verification email</strong> (ADR-005), and
     * for a reason beyond tidiness: this outcome's whole job is to be
     * indistinguishable from {@link Registered}, and a sender called on one
     * path and not the other is the difference measurable with a stopwatch.
     * Two paths that both publish an event and return are the same shape as
     * well as the same page.
     */
    record AddressTaken() implements RegistrationOutcome { }
}
