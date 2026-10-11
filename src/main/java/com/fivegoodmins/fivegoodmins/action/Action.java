package com.fivegoodmins.fivegoodmins.action;

import com.fivegoodmins.fivegoodmins.user.AppUser;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "action")
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ActionType actionType;

    @Column(name = "external_url", length = 2048)
    private String externalUrl;

    @Column(name = "normalized_url", length = 500)
    private String normalizedUrl;

    @Column(name = "video_url", length = 2048)
    private String videoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionStatus status = ActionStatus.PENDING;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "action_category",
            joinColumns = @JoinColumn(name = "action_id"))
    @Column(name = "category", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Category> categories = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "action_region",
            joinColumns = @JoinColumn(name = "action_id"))
    @Column(name = "region", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Region> regions = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // search_vector is deliberately not mapped: Postgres computes it from title
    // and description on every write, and nothing in Java ever reads it. An
    // unmapped column is invisible to ddl-auto=validate, which only checks that
    // the columns the entity *does* declare exist with matching types.

    protected Action() { }   // required by JPA, not for your code

    public Action(AppUser author, String title, String description,
                  ActionType actionType, Instant endsAt) {
        this.author = author;
        this.title = title;
        this.description = description;
        this.actionType = actionType;
        this.endsAt = endsAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Action that)) return false;
        return id != null && id.equals(that.getId());   // getId(), not that.id
    }

    @Override
    public int hashCode() {
        return Action.class.hashCode();
    }

    // -----------------------------------------------------------------------
    // State transitions (ADR-016).
    //
    // These are the only way status and published_at change. There is no
    // setStatus and no setPublishedAt on purpose: published_at must be written
    // exactly once ever, because the digest watermark (ADR-005) treats it as
    // immutable, and a public setter would make the whole pattern unsound.
    // ck_action_published_at_required in the database is the backstop for code
    // paths that do not go through this class, not the primary defence.
    // -----------------------------------------------------------------------

    /** Sets published_at exactly once, ever. The digest depends on this. */
    public void publish(Instant now) {
        this.status = ActionStatus.PUBLISHED;
        if (this.publishedAt == null) this.publishedAt = now;
    }

    public void withdraw() {
        if (status != ActionStatus.PUBLISHED)
            throw new IllegalStateException("only a published action can be withdrawn");
        this.status = ActionStatus.WITHDRAWN;
    }

    public void reject()          { this.status = ActionStatus.REJECTED; }
    public void returnToPending() { this.status = ActionStatus.PENDING; }

    /** True once the action has been published at least once, whatever its
     *  current status. This is the fact the POSTER threshold counts, not the
     *  current status, because the state graph is cyclic (ADR-016). */
    public boolean hasBeenPublished() { return publishedAt != null; }

    public void setLink(String externalUrl, String normalizedUrl) {
        if ((externalUrl == null) != (normalizedUrl == null))
            throw new IllegalArgumentException("both URL columns or neither");
        this.externalUrl = externalUrl;
        this.normalizedUrl = normalizedUrl;
    }

    public boolean isExpired(Instant now) { return endsAt.isBefore(now); }

    // -----------------------------------------------------------------------
    // Accessors. No setId (the database assigns it), no setStatus or
    // setPublishedAt (see above), no setCreatedAt or setUpdatedAt (Hibernate's
    // @CreationTimestamp / @UpdateTimestamp own them and overwrite anything a
    // setter wrote at flush time, so such a setter is a trap, not an API).
    // -----------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public AppUser getAuthor() {
        return author;
    }

    public void setAuthor(AppUser author) {
        this.author = author;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public ActionStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Set<Category> getCategories() {
        return categories;
    }

    public void setCategories(Set<Category> categories) {
        this.categories = categories;
    }

    public Set<Region> getRegions() {
        return regions;
    }

    public void setRegions(Set<Region> regions) {
        this.regions = regions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
