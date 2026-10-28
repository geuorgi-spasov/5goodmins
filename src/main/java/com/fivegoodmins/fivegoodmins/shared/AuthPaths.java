package com.fivegoodmins.fivegoodmins.shared;

/**
 * The URL paths of the registration and login flow, in one place because four
 * files have to agree on them and three of them cannot see each other.
 *
 * <p>Two of these paths are a <strong>wire format</strong> rather than an
 * internal detail. {@code VERIFY_EMAIL} is in every verification email that
 * has ever been sent and stays valid for seven days; {@code LOGIN} is in every
 * "you already have an account" mail. Renaming either breaks links already
 * sitting in mailboxes, the same way renaming a {@code TokenPurpose} constant
 * does -- so the rename is a redirect from the old path, not an edit here.
 *
 * <p><strong>In {@code shared} rather than in {@code user}, deliberately.</strong>
 * {@code RegistrationEmailListener} builds the links, {@code RegistrationController}
 * and {@code LoginController} serve them, and {@code SecurityConfig} decides
 * who may reach them -- across {@code user} and {@code shared.security}, which
 * under ADR-003 do not depend on each other. A constant they all read from
 * {@code shared} is the dependency direction that does not create a cycle.
 *
 * <p>Before this existed the listener held private copies with a comment
 * saying they were "the specification of what the controllers must be called,
 * and the test is what notices if they change on one side only". They are now
 * one string, so there is no side to change.
 */
public final class AuthPaths {

    /** Page 6 of the frontend brief: the registration form. GET renders, POST submits. */
    public static final String REGISTER = "/register";

    /** The "check your inbox" state of page 8; the GET half of the form's POST. */
    public static final String CHECK_EMAIL = "/register/check-email";

    /** Page 8's result states. Carries {@link #TOKEN_PARAMETER}. */
    public static final String VERIFY_EMAIL = "/verify-email";

    /** Page 7, and the path {@code formLogin().loginPage(...)} names. */
    public static final String LOGIN = "/login";

    /**
     * Page 5 of the frontend brief, and here rather than in a holder of its
     * own because the registration form's consent checkbox is what links to
     * it: a GDPR consent whose policy link does not resolve is not a consent
     * worth collecting, so the two belong in one place.
     */
    public static final String PRIVACY = "/privacy";

    /** The query parameter on {@link #VERIFY_EMAIL}. */
    public static final String TOKEN_PARAMETER = "token";

    private AuthPaths() {
    }
}
