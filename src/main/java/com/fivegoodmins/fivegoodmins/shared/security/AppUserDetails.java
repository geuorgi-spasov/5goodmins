package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal: a copy of the four things the security layer and
 * the controllers need, taken out of {@link AppUser} at login and detached from
 * it afterwards.
 *
 * <p>A copy rather than the entity itself, for two reasons. Entities never leave
 * the service layer (ADR-013), and this object lives in the session for the
 * length of the login -- an entity there would be a detached instance of
 * unknown age handed to every request, which is how stale reads and
 * {@code LazyInitializationException} get into a codebase that has
 * {@code open-in-view=false} precisely to avoid them.
 *
 * <p>It carries the database id, which Spring Security's own
 * {@code org.springframework.security.core.userdetails.User} cannot. Ownership
 * is the commonest authorization question this application has -- is this the
 * author of this action (ADR-016), whose subscriptions are these, whose account
 * is being deleted (ADR-015) -- and all of them compare against app_user.id.
 * Without the id in the principal every one of those checks is a lookup by
 * email first.
 */
public final class AppUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean loginAllowed;

    AppUserDetails(AppUser user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        // Both halves of "may this account log in at all": the ban switch and
        // mandatory email verification (ADR-002). AppUserDetailsService has
        // already refused to return such a user, so this is always true on the
        // path that constructs it -- it is here so that deleting that check
        // fails closed rather than opening login to unverified accounts.
        this.loginAllowed = user.isEnabled() && user.isEmailVerified();
    }

    /** app_user.id. Never null: the principal is built from a persisted row. */
    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // One authority, because one role. The ROLE_ prefix is Spring
        // Security's convention and what hasRole() re-adds before comparing;
        // the hierarchy in SecurityConfig expands ADMIN to POSTER and USER.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** The {@code {bcrypt}}-prefixed hash, for the authentication provider. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * The email address, lowercased. Spring Security's "username" is whatever
     * identifies the account, and this application has no separate username:
     * the address is the login identifier and the digest destination both.
     */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return loginAllowed;
    }

    // isAccountNonExpired, isAccountNonLocked and isCredentialsNonExpired are
    // default methods returning true on UserDetails, and all three stay that
    // way: there is no password expiry, and ADR-002 rejects account lockout in
    // favour of rate limiting, so no state here can ever make them false.

    /**
     * Deliberately omits the password hash. A principal ends up in log lines,
     * exception messages and debugger output, and the generated {@code toString}
     * of a record or an IDE-written one would carry the hash into all three.
     */
    @Override
    public String toString() {
        return "AppUserDetails[id=" + id + ", email=" + email + ", role=" + role + "]";
    }
}
