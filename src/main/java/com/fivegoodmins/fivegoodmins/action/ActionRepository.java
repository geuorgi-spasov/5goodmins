package com.fivegoodmins.fivegoodmins.action;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ActionRepository extends JpaRepository<Action, Long> {

    Optional<Action> findByNormalizedUrl(String normalizedUrl);

    /** Submission quota -- ten per rolling 24h (ADR-016). */
    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant since);

    /**
     * Floor for promotion to POSTER -- three approved actions (ADR-016).
     *
     * Counts published_at, not status = PUBLISHED. The state graph is cyclic, so
     * status is the *current* state while the threshold asks a *historical*
     * question: has an admin ever approved this action? An author who withdrew
     * two of five approved actions would otherwise count as three, and one whose
     * action was later taken down would lose credit for work that was approved.
     * published_at is written once and never cleared, so it is the record of
     * "has been published at least once".
     */
    long countByAuthorIdAndPublishedAtIsNotNull(Long authorId);
}
