package com.fivegoodmins.fivegoodmins.action;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Import(TestcontainersConfiguration.class)
@DataJpaTest
class ActionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired ActionRepository actions;
    @Autowired AppUserRepository users;
    @Autowired EntityManager em;

    private AppUser author(String email) {
        return users.save(new AppUser(email, "{bcrypt}x", "Georgi", NOW));
    }

    private Action action(AppUser author, String title) {
        return new Action(author, title, "opisanie", ActionType.PETITION,
                NOW.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void roundTripsCategoriesAndRegionsThroughTheirJoinTables() {
        Action a = action(author("rt@seed.example"), "Spasi gorata nad Sofia");
        a.setCategories(Set.of(Category.NATURE, Category.LOCAL_COMMUNITIES));
        a.setRegions(Set.of(Region.SOFIA_CITY, Region.NATIONAL));
        actions.saveAndFlush(a);
        em.clear();   // force a real read, not the persistence context's copy

        Action loaded = actions.findById(a.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ActionStatus.PENDING);   // fails closed
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getCategories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.LOCAL_COMMUNITIES);
        assertThat(loaded.getRegions())
                .containsExactlyInAnyOrder(Region.SOFIA_CITY, Region.NATIONAL);
    }

    @Test
    void writesPublishedAtOnceAndNeverAgain() {
        Action a = action(author("pub@seed.example"), "Spasi gorata nad Sofia");
        a.publish(NOW);
        actions.saveAndFlush(a);

        a.reject();
        a.publish(NOW.plus(10, ChronoUnit.DAYS));   // an admin reverses the decision
        actions.saveAndFlush(a);

        // Re-stamping would mail the action to every subscriber again (ADR-005).
        assertThat(a.getPublishedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void anyNumberOfLinklessActionsCoexistBecauseNullsAreDistinct() {
        AppUser author = author("linkless@seed.example");
        actions.saveAndFlush(action(author, "Prva akcia"));
        actions.saveAndFlush(action(author, "Vtora akcia"));

        assertThat(actions.count()).isEqualTo(2);
    }

    @Test
    void refusesASecondActionOnTheSameNormalizedUrlAndNamesTheConstraint() {
        AppUser author = author("dup@seed.example");
        Action first = action(author, "Prva akcia");
        first.setLink("https://example.org/p/1", "example.org/p/1");
        actions.saveAndFlush(first);

        // Same campaign, different spelling: www., tracking params, trailing slash.
        Action second = action(author, "Vtora akcia");
        second.setLink("https://www.EXAMPLE.org/p/1/?utm_source=fb", "example.org/p/1");

        // Identifying the constraint *by name* is what lets the service show
        // "this link is already on the platform" instead of a 500 (ADR-011).
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> actions.saveAndFlush(second))
                .withMessageContaining("uq_action_normalized_url");
    }

    @Test
    void countsEveryActionThatWasEverApprovedNotOnlyThoseStillPublished() {
        AppUser author = author("threshold@seed.example");

        Action live = action(author, "Prva akcia");
        live.publish(NOW);
        actions.saveAndFlush(live);

        Action withdrawnByItsPoster = action(author, "Vtora akcia");
        withdrawnByItsPoster.publish(NOW);
        withdrawnByItsPoster.withdraw();
        actions.saveAndFlush(withdrawnByItsPoster);

        Action takenDownByAnAdmin = action(author, "Treta akcia");
        takenDownByAnAdmin.publish(NOW);
        takenDownByAnAdmin.reject();
        actions.saveAndFlush(takenDownByAnAdmin);

        Action neverApproved = action(author, "Chetvarta akcia");
        actions.saveAndFlush(neverApproved);

        // All three were approved by an admin at some point, so the author has
        // cleared the POSTER floor. Counting status = PUBLISHED would say one.
        assertThat(actions.countByAuthorIdAndPublishedAtIsNotNull(author.getId()))
                .isEqualTo(3);
    }

    @Test
    void countsOnlyTodaysSubmissionsAgainstTheQuota() {
        AppUser author = author("quota@seed.example");
        actions.saveAndFlush(action(author, "Prva akcia"));
        actions.saveAndFlush(action(author, "Vtora akcia"));

        // created_at is Hibernate's clock, so the window is relative to real now.
        assertThat(actions.countByAuthorIdAndCreatedAtAfter(
                author.getId(), Instant.now().minus(1, ChronoUnit.DAYS))).isEqualTo(2);
        assertThat(actions.countByAuthorIdAndCreatedAtAfter(
                author.getId(), Instant.now().plus(1, ChronoUnit.MINUTES))).isZero();
    }

    @Test
    void refusesAWithdrawnActionThatWasNeverPublished() {
        // Unreachable through the entity's own API -- withdraw() throws unless
        // the action is PUBLISHED. The CHECK is the backstop for a future code
        // path that writes status without going through this class.
        Action a = action(author("backstop@seed.example"), "Peta akcia");
        actions.saveAndFlush(a);

        // Native SQL runs at once rather than at the next flush, which is
        // exactly the kind of code path the CHECK exists for. The exception is
        // JPA's, not Spring's DataIntegrityViolationException: translation into
        // the Spring DAO hierarchy happens at the repository proxy boundary, and
        // this call goes straight to the EntityManager.
        assertThatExceptionOfType(PersistenceException.class)
                .isThrownBy(() -> em.createNativeQuery(
                                "UPDATE action SET status = 'WITHDRAWN' WHERE id = :id")
                        .setParameter("id", a.getId())
                        .executeUpdate())
                .withMessageContaining("ck_action_published_at_required");
    }
}
