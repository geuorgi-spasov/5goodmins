package com.fivegoodmins.fivegoodmins.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What stands in for a transport when none is configured.
 *
 * <p>It exists because of one unfinished decision and one requirement that
 * cannot wait for it. The production transport is a provider's HTTP API and the
 * provider is not chosen yet (§5, week 7), but the deployed site still has to
 * start and serve the pages that do not send email. A context that refuses to
 * come up because {@code spring.mail.host} is unset would make the browse-only
 * site depend on a decision that belongs to a later week.
 *
 * <p><strong>It refuses rather than discards.</strong> A no-op sender is the
 * version of this class that causes a bad afternoon: registrations would look
 * successful, no mail would arrive, and nothing anywhere would say so. Throwing
 * puts the gap on the same path as a provider outage -- which ADR-005 already
 * requires to be logged loudly -- so "no transport configured" and "the
 * transport is down" are reported the same way instead of one of them being
 * silent.
 *
 * <p>It is also what every {@code @SpringBootTest} gets, which is deliberate:
 * a test that sends email unexpectedly fails with this exception instead of
 * opening a socket to a mail server.
 */
class UnconfiguredEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredEmailSender.class);

    UnconfiguredEmailSender() {
        log.warn("No email transport configured (spring.mail.host is unset): "
                + "any attempt to send will fail. Development uses Mailpit; "
                + "production needs the provider chosen in ADR-005 / section 5.");
    }

    @Override
    public void send(EmailMessage message) {
        throw new EmailDeliveryException(
                "no email transport is configured; set spring.mail.host or supply an EmailSender",
                null);
    }
}
