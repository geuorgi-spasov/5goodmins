package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers from ADR-002, pinned.
 *
 * <p>Asserted for the same reason the HSTS directive set is asserted rather
 * than the presence of {@code max-age}: these are policy, settled with a
 * rationale, and a change to any of them should fail a build and be argued
 * for -- not land quietly because somebody was adjusting a default while
 * debugging something else. The suite runs with
 * {@code app.rate-limit.enabled=false}, so this is also the only place the
 * shipped defaults are observed at all.
 */
class RateLimitPropertiesTest {

    private static RateLimitProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.rate-limit", Bindable.of(RateLimitProperties.class));
    }

    @Test
    void shipsAdr002sNumbersWithNothingConfigured() {
        RateLimitProperties limits = bind(Map.of());

        assertThat(limits.enabled()).as("on unless explicitly switched off").isTrue();

        assertThat(limits.loginAttempts()).isEqualTo(20);
        assertThat(limits.loginWindow()).isEqualTo(Duration.ofMinutes(15));

        assertThat(limits.registrationsPerAddress()).isEqualTo(10);
        assertThat(limits.registrationsPerAddressWindow()).isEqualTo(Duration.ofHours(1));

        assertThat(limits.registrationEmailsPerRecipient()).isEqualTo(3);
        assertThat(limits.registrationEmailsPerRecipientWindow()).isEqualTo(Duration.ofHours(24));

        assertThat(limits.maxTrackedKeys()).isEqualTo(20_000);
    }

    /**
     * The incident switch, which is the one property anybody will ever set in
     * production, and the one the whole suite depends on.
     */
    @Test
    void canBeSwitchedOffWithoutRestatingEveryNumber() {
        RateLimitProperties limits = bind(Map.of("app.rate-limit.enabled", "false"));

        assertThat(limits.enabled()).isFalse();
        assertThat(limits.loginAttempts()).isEqualTo(20);
    }

    @Test
    void readsEachScopesCapacityAndWindowBackByScope() {
        RateLimitProperties limits = bind(Map.of());

        assertThat(limits.capacityOf(RateLimitScope.LOGIN_ATTEMPTS)).isEqualTo(20);
        assertThat(limits.capacityOf(RateLimitScope.REGISTRATIONS_PER_ADDRESS)).isEqualTo(10);
        assertThat(limits.capacityOf(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT)).isEqualTo(3);

        assertThat(limits.windowOf(RateLimitScope.LOGIN_ATTEMPTS))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(limits.windowOf(RateLimitScope.REGISTRATIONS_PER_ADDRESS))
                .isEqualTo(Duration.ofHours(1));
        assertThat(limits.windowOf(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT))
                .isEqualTo(Duration.ofHours(24));
    }
}
