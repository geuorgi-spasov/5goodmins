package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's source of "now".
 *
 * <p>A bean so that nothing has to call {@code Instant.now()} internally
 * (CLAUDE.md). The rule exists because time read from inside a method is time a
 * test cannot set: the digest watermark (ADR-005), the token expiries in
 * {@code shared/token} and the seven-day purge of unverified rows (ADR-002) are
 * all logic whose only interesting cases are at a boundary, and reaching those
 * through the system clock means either sleeping or not testing them.
 *
 * <p>{@code systemUTC}, not {@code systemDefaultZone}: everything stored is
 * {@code timestamptz} read as {@link java.time.Instant}, so a zone here would
 * only be a default waiting to be picked up by something that should not have
 * one. Display-time zone conversion is the template's business.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
