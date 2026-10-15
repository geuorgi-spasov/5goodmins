package com.fivegoodmins.fivegoodmins.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * The caller lowercases first. ck_app_user_email_lowercase guarantees every
     * stored address is already lowercase, so a plain equality match is both
     * case-insensitive in effect and able to use uq_app_user_email.
     */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * The seven-day purge of accounts that never confirmed their address
     * (ADR-002). Returns how many rows went.
     *
     * <p><strong>A bulk statement, not a derived {@code deleteBy...} query.</strong>
     * The derived form selects the matching rows into the persistence context
     * and issues one DELETE each, which is the pattern CLAUDE.md rejects for
     * JPA-side cascading and is no better here: the set is unbounded -- it is
     * however many stale registrations accumulated -- and nothing in this
     * transaction needs the entities. One statement, bounded memory.
     *
     * <p>{@code flushAutomatically}, because a bulk statement is executed by
     * the database and sees only what has actually been written. A caller that
     * had verified an account earlier in the same transaction, without that
     * change having reached Postgres, would watch this delete the row it just
     * confirmed. The current caller holds nothing, so this costs an empty
     * flush; the day one does, it is the difference between a cleanup and
     * deleting a live account.
     *
     * <p>No {@code clearAutomatically}. The trap with a bulk delete is a
     * persistence context still holding a row the database no longer has, and
     * the only caller is a scheduled job whose transaction contains this and
     * nothing else (ADR-012). Were this ever called from a flow that had
     * already loaded a user, that flag -- or not calling it from there -- would
     * be the fix.
     *
     * <p>The FK from action to app_user is {@code ON DELETE CASCADE}, so
     * Postgres removes anything attached without JPA loading it (ADR-015). In
     * practice there is nothing to remove: an unverified account cannot log in,
     * so it cannot have authored anything.
     *
     * @param cutoff rows created strictly before this are deleted. Passed in
     *               rather than computed here, so the window is the service's
     *               decision and a test can place it where it likes.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from AppUser u where u.emailVerifiedAt is null and u.createdAt < :cutoff")
    int deleteUnverifiedCreatedBefore(@Param("cutoff") Instant cutoff);
}
