package com.fivegoodmins.fivegoodmins.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two beans whose behaviour is a decision rather than a default. No Spring
 * context: both bean methods are plain factory methods.
 */
class SecurityConfigTest {

    private final RoleHierarchy hierarchy = SecurityConfig.roleHierarchy();
    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    private List<String> reachableFrom(String role) {
        return hierarchy
                .getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_" + role)))
                .stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    @Test
    void anAdminReachesEveryRoleBelowIt() {
        // The point of the hierarchy: a rule written as hasRole("USER") holds
        // for an admin without the rule naming all three roles, even though a
        // user has exactly one role in exactly one column.
        assertThat(reachableFrom("ADMIN"))
                .containsExactly("ROLE_ADMIN", "ROLE_POSTER", "ROLE_USER");
    }

    @Test
    void aPosterReachesUserButNotAdmin() {
        assertThat(reachableFrom("POSTER")).containsExactly("ROLE_POSTER", "ROLE_USER");
    }

    @Test
    void aPlainUserReachesNothingElse() {
        // The direction matters, and getting it backwards is the classic
        // mistake: an inverted hierarchy makes every registered user an admin
        // and no test of a happy path notices.
        assertThat(reachableFrom("USER")).containsExactly("ROLE_USER");
    }

    @Test
    void encodesNewPasswordsAsPrefixedBcryptAndVerifiesThemBack() {
        String encoded = encoder.encode("correct-horse-battery-staple");

        // The prefix is what makes a later move to Argon2id a change of default
        // rather than a migration: an old row still says how to read itself.
        assertThat(encoded).startsWith("{bcrypt}$2");
        assertThat(encoder.matches("correct-horse-battery-staple", encoded)).isTrue();
        assertThat(encoder.matches("not-the-password", encoded)).isFalse();

        // 68 characters for BCrypt against app_user.password_hash's
        // varchar(255), which was sized for the 105 of {argon2} (ADR-013).
        assertThat(encoded.length()).isLessThanOrEqualTo(255);
    }
}
