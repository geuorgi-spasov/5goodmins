package com.fivegoodmins.fivegoodmins.action;

import com.fivegoodmins.fivegoodmins.action.Action;
import com.fivegoodmins.fivegoodmins.action.ActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ActionRepository extends JpaRepository<Action, Long> {

    Optional<Action> findByNormalizedUrl(String normalizedUrl);

    /** Submission quota — ten per rolling 24h (ADR-016). */
    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant since);

    /** Floor for promotion to POSTER — three approved actions (ADR-016). */
    long countByAuthorIdAndStatus(Long authorId, ActionStatus status);
}