package com.fivegoodmins.fivegoodmins.user;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;
    // Uniqueness is uq_app_user_email in the Flyway migration. unique = true
    // here would be DDL-generation metadata only -- ddl-auto=validate never
    // reads it -- so stating it would imply Hibernate enforces something it
    // does not.

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "poster_verified_at")
    private Instant posterVerifiedAt;

    @Column(name = "last_digest_sent_at", nullable = false)
    private Instant lastDigestSentAt;

    @Column(nullable = false, length = 2)
    private String locale = "bg";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() { }   // required by JPA, not for your code

    /**
     * @param email   already lowercased and trimmed by the caller; the database
     *                verifies it with ck_app_user_email_lowercase rather than
     *                converting it (the application normalizes, the database
     *                verifies).
     * @param now     the digest watermark's starting point, so the first digest
     *                contains only actions published after the account existed
     *                (ADR-005). Passed in rather than read from Instant.now()
     *                so a test can inject a fixed Clock.
     */
    public AppUser(String email, String passwordHash, String displayName, Instant now) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.lastDigestSentAt = now;
    }

    /**
     * The canonical form of a login address: trimmed, and lowercased under
     * {@link Locale#ROOT}.
     *
     * <p>Static and here, on the entity whose constructor states the
     * contract, because two separate paths have to agree on it exactly --
     * registration, which writes the row, and
     * {@code AppUserDetailsService}, which looks it up. The database holds
     * them to it with ck_app_user_email_lowercase and matches on plain
     * equality through uq_app_user_email, so a path that normalizes
     * differently does not produce a near-miss: it produces a registration
     * that fails on a CHECK, or a login that matches no row.
     *
     * <p>{@code Locale.ROOT} is not decoration. Under a Turkish locale
     * {@code "I".toLowerCase()} is "\u0131", so an address containing I would
     * normalize to something uq_app_user_email cannot match -- a login that
     * works or not depending on the server's default locale.
     *
     * <p>Null becomes the empty string rather than throwing. Both callers
     * receive this from a form, where absent and blank are the same mistake,
     * and an empty address matches no row and fails validation.
     */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppUser that)) return false;
        return id != null && id.equals(that.getId());   // getId(), not that.id
    }

    @Override
    public int hashCode() {
        return AppUser.class.hashCode();
    }

    public boolean isEmailVerified() { return emailVerifiedAt != null; }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) this.emailVerifiedAt = now;
    }

    // -----------------------------------------------------------------------
    // Accessors. No setId (the database assigns it), no setCreatedAt or
    // setUpdatedAt: @CreationTimestamp / @UpdateTimestamp own those columns and
    // overwrite anything a setter wrote at flush time.
    // -----------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    /** The caller lowercases and trims; see the constructor. */
    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getPosterVerifiedAt() {
        return posterVerifiedAt;
    }

    public void setPosterVerifiedAt(Instant posterVerifiedAt) {
        this.posterVerifiedAt = posterVerifiedAt;
    }

    public Instant getLastDigestSentAt() {
        return lastDigestSentAt;
    }

    public void setLastDigestSentAt(Instant lastDigestSentAt) {
        this.lastDigestSentAt = lastDigestSentAt;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
