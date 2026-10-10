package com.fivegoodmins.fivegoodmins.user;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254, unique = true)
    private String email;

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
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

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
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

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Column(name = "password_hash", nullable = false, length = 100)
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

    public AppUser(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.lastDigestSentAt = Instant.now();
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

    // getters; setters only for fields that are genuinely edited
    // (displayName, bio, websiteUrl, locale, passwordHash, enabled, role, lastDigestSentAt)
    // no setId, no setCreatedAt
}