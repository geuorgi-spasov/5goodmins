package com.fivegoodmins.fivegoodmins.shared.ratelimit;

/**
 * The three buckets this application has, and deliberately no mechanism for a
 * fourth: ADR-002 says rate limiting on {@code /login} and {@code /register}
 * only, and "don't build a general-purpose rate limiter".
 *
 * <p>An enum rather than a string scope, so that adding one is a visible change
 * to this file and to {@link RateLimitProperties}, which the compiler then
 * forces to grow a capacity and a window for it.
 */
enum RateLimitScope {

    /** {@code POST /login}, keyed on the client address. */
    LOGIN_ATTEMPTS,

    /** {@code POST /register}, keyed on the client address. */
    REGISTRATIONS_PER_ADDRESS,

    /**
     * {@code POST /register}, keyed on the submitted email address -- the
     * bucket that protects somebody else's inbox rather than this server.
     */
    REGISTRATIONS_PER_EMAIL
}
