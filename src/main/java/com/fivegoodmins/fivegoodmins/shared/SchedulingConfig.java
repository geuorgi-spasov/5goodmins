package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} (ADR-012).
 *
 * <p><strong>This assumes exactly one instance of the application is
 * running.</strong> Spring's scheduler is in-process and knows nothing about
 * its peers, so a second instance runs every job a second time. For the
 * nightly digest (ADR-005) that would mean every subscriber receiving two
 * emails; it is therefore the single thing in this design that blocks
 * horizontal scaling, and it is accepted knowingly for the MVP rather than
 * overlooked.
 *
 * <p>The fix when it matters is ShedLock or a Postgres advisory lock around
 * each job, or moving the scheduled work to a separate single-replica
 * deployment. Not now: it is infrastructure for a problem one Railway service
 * does not have.
 *
 * <p>A configuration class of its own rather than the annotation on
 * {@code FivegoodminsApplication}, so that the paragraph above has somewhere
 * to live and so the assumption is a file someone can find.
 *
 * <p>One thread, which is the default. The jobs this application has are a
 * nightly purge and a nightly digest; they are not concurrent, and a pool
 * sized for contention that does not exist would only hide it when it starts.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
