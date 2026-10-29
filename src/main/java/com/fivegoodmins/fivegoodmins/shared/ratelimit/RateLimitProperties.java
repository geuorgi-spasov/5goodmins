package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The numbers behind ADR-002's rate limiting, and the only place they are
 * written down in code.
 *
 * <p><strong>Defaults live here, not in {@code application.yaml}.</strong>
 * Unlike the datasource and the signing key -- absent on purpose, so a missing
 * value fails at startup rather than being silently wrong -- a missing limit
 * has an obviously correct answer, and the deployed site must carry the policy
 * without anyone remembering to configure it. They are nevertheless properties
 * rather than constants, because a limit that cannot be changed without a
 * build is a limit that cannot be relaxed while something is on fire.
 *
 * <p><strong>Why these numbers</strong> (settled with ADR-002's Rev 19 line):
 *
 * <ul>
 * <li><strong>{@code login-attempts = 20} per 15 minutes, per client
 * address.</strong> A person who knows their password needs one attempt and a
 * person who has forgotten it needs about five, so 20 leaves room for four
 * people fumbling behind a single carrier-grade NAT address -- which in
 * Bulgaria is most mobile traffic. For an attacker it caps a run at 80
 * guesses an hour from one address, which makes a thousand-password list a
 * half-day job and a credential-stuffing list of thousands pointless. It is
 * deliberately not tight enough to stop a slow distributed run; that is what
 * ADR-002 names Turnstile for.</li>
 * <li><strong>{@code registrations-per-address = 10} per hour.</strong>
 * Registration is a once-ever act, so any number above one is there purely for
 * shared addresses -- a classroom or an office signing up after a talk. Ten an
 * hour covers that and still stops a single host enumerating.</li>
 * <li><strong>{@code registrations-per-email = 3} per day.</strong> This is
 * the bucket that matters, and the reason ADR-002 asks for a second one:
 * {@code register} emails the owner of an unverified address once per request,
 * so without a per-address cap the form is a mail cannon aimed at somebody
 * else's inbox. Three a day is enough for a person who really is registering,
 * retrying and registering again, and far too few to be a nuisance.</li>
 * </ul>
 *
 * <p><strong>{@code enabled} is an incident switch, not a feature flag.</strong>
 * If the limiter ever starts refusing real users -- a stale Cloudflare range
 * collapsing every request onto one bucket is the documented way for that to
 * happen -- turning it off is one environment variable and a restart, rather
 * than a revert and a deploy. It is also what the existing web tests set, so
 * that a suite which POSTs the registration form twenty times is not testing
 * the limiter by accident.
 *
 * <p>{@code max-tracked-keys} bounds each scope's bucket store; see
 * {@link RateLimiter} for what eviction costs.
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record RateLimitProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue("20") @Min(1) int loginAttempts,
        @DefaultValue("15m") @NotNull Duration loginWindow,

        @DefaultValue("10") @Min(1) int registrationsPerAddress,
        @DefaultValue("1h") @NotNull Duration registrationsPerAddressWindow,

        @DefaultValue("3") @Min(1) int registrationsPerEmail,
        @DefaultValue("24h") @NotNull Duration registrationsPerEmailWindow,

        @DefaultValue("20000") @Min(1) int maxTrackedKeys) {

    int capacityOf(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN_ATTEMPTS -> loginAttempts;
            case REGISTRATIONS_PER_ADDRESS -> registrationsPerAddress;
            case REGISTRATIONS_PER_EMAIL -> registrationsPerEmail;
        };
    }

    Duration windowOf(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN_ATTEMPTS -> loginWindow;
            case REGISTRATIONS_PER_ADDRESS -> registrationsPerAddressWindow;
            case REGISTRATIONS_PER_EMAIL -> registrationsPerEmailWindow;
        };
    }
}
