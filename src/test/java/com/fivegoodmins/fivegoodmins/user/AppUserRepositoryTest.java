package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The existence of this class is half the test: AppUserRepository spent a
 * commit in the default package, where Spring's component scan could not see it
 * and no other package could import it. It compiled, and the only test in the
 * suite passed, because nothing ever asked for the bean.
 */
@Import(TestcontainersConfiguration.class)
@DataJpaTest
class AppUserRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired
    AppUserRepository users;

    private AppUser user(String email) {
        return new AppUser(email, "{bcrypt}$2a$10$7EqJtq98hPqEX7fNZaFWoO", "Georgi", NOW);
    }

    @Test
    void findsAUserByItsLowercasedEmail() {
        users.saveAndFlush(user("georgi@seed.example"));

        assertThat(users.findByEmail("georgi@seed.example")).isPresent();
        assertThat(users.existsByEmail("georgi@seed.example")).isTrue();
        assertThat(users.findByEmail("nobody@seed.example")).isEmpty();
    }

    @Test
    void storesTheDigestWatermarkItWasGivenRatherThanReadingTheSystemClock() {
        AppUser saved = users.saveAndFlush(user("watermark@seed.example"));

        // Truncated to micros: Instant carries nanos, timestamptz stores micros.
        assertThat(saved.getLastDigestSentAt())
                .isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void refusesAnEmailThatTheApplicationForgotToLowercase() {
        // The application normalizes, the database verifies. This is the one
        // path that proves the second half is real.
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> users.saveAndFlush(user("Georgi@Seed.Example")))
                .withMessageContaining("ck_app_user_email_lowercase");
    }

    @Test
    void refusesADisplayNameThatIsOnlyWhitespace() {
        AppUser blank = new AppUser("blank@seed.example", "{bcrypt}x", "   ", NOW);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> users.saveAndFlush(blank))
                .withMessageContaining("ck_app_user_display_name_length");
    }

    @Test
    void storesTheRoleAsAStringSoReorderingTheEnumCannotCorruptRows() {
        AppUser poster = user("poster@seed.example");
        poster.setRole(Role.POSTER);
        poster.setPosterVerifiedAt(NOW);
        users.saveAndFlush(poster);

        assertThat(users.findByEmail("poster@seed.example"))
                .get()
                .extracting(AppUser::getRole)
                .isEqualTo(Role.POSTER);
    }

    @Test
    void refusesAPosterVerifiedTimestampOnAPlainUser() {
        AppUser inconsistent = user("inconsistent@seed.example");
        inconsistent.setPosterVerifiedAt(NOW);   // role is still USER

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> users.saveAndFlush(inconsistent))
                .withMessageContaining("ck_app_user_poster_verified_role");
    }
}
