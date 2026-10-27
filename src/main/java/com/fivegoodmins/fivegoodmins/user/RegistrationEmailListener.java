package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.shared.email.EmailComposer;
import com.fivegoodmins.fivegoodmins.shared.email.EmailConfig;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailProperties;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.shared.email.EmailTemplate;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Sends the two registration emails, after the transaction that caused them has
 * committed and off the thread that is answering the user (ADR-005).
 *
 * <p>Two mechanisms, two different bugs, and keeping them apart is the point:
 *
 * <ul>
 *   <li><strong>{@code AFTER_COMMIT} is correctness.</strong> A send inside the
 *       transaction goes out even when the transaction later rolls back, so
 *       someone receives a verification link for an account that does not
 *       exist -- and because the token signs a user id that was never
 *       committed, that link resolves to nothing for as long as it lives.</li>
 *   <li><strong>The executor is the timing oracle.</strong> An after-commit
 *       listener runs on the publishing thread, so without one the HTTP
 *       response waits for the mail provider: 100-500ms on the path that
 *       registers and none on the path that refuses, which tells a stopwatch
 *       what the identical pages will not.</li>
 * </ul>
 *
 * <p><strong>The submit is explicit rather than {@code @Async}, and that is a
 * measured decision.</strong> ADR-005 specified the annotation, and it works --
 * until the queue fills. Rejection happens at submit time, which with
 * {@code @Async} is inside Spring's transaction synchronization: the
 * {@code TaskRejectedException} propagates out of the proxy, past this class
 * entirely, and lands in {@code TransactionSynchronizationUtils}, which logs
 * <em>"TransactionSynchronization.afterCompletion threw exception"</em> and the
 * stack trace. No account id, no address, nothing saying it was an email --
 * because at that point the event is no longer in scope. Neither the catch
 * below nor an {@code AsyncUncaughtExceptionHandler} sees it; both cover
 * failures <em>inside</em> the task. Measured by saturating the queue, not
 * reasoned about. Submitting by hand is three lines and puts the rejection
 * where the event still is.
 *
 * <p>What the explicit submit costs: the request thread does the submitting, a
 * few microseconds, on both registration paths equally. What it buys: the one
 * failure this design is built to produce is attributable.
 *
 * <p><strong>A failure rolls nothing back</strong>, which is right -- an
 * account should not be undone because a mail provider was briefly down -- and
 * is therefore only as good as its logging. Both failure modes are logged with
 * the user id: the send that threw, and the send that was never attempted.
 *
 * <p><strong>The user's half of that has to exist too.</strong> With no resend
 * form (ADR-002), their recovery is to register again -- which replaces the
 * row and issues a fresh link, so it really is a resend -- and the rate limit
 * on {@code /register} is what keeps it from being a way to flood a mailbox.
 * A recovery nobody is told about is not one, so the "check your inbox" page
 * carries it (frontend brief, page 8; the strings are
 * {@code verification.notice.spam} and {@code verification.notice.resend}).
 */
@Component
class RegistrationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailListener.class);

    /**
     * The path the emailed link lands on, and the login page the
     * already-registered mail points at.
     *
     * <p>Built here because an after-commit listener has no request to derive a
     * host from, and the host it would derive is Tomcat's rather than the
     * public one. Both paths are served by controllers that do not exist yet
     * (frontend brief, pages 7 and 8); until they do, these two constants are
     * the specification of what they must be called, and
     * {@code RegistrationEmailListenerTest} is what notices if they change on
     * one side only.
     */
    private static final String VERIFY_PATH = "/verify-email";
    private static final String LOGIN_PATH = "/login";

    private static final String TOKEN_PARAMETER = "token";

    private static final EmailTemplate VERIFICATION =
            new EmailTemplate("email/verification", "email.verification.subject");

    private static final EmailTemplate EXISTING_ACCOUNT =
            new EmailTemplate("email/existing-account", "email.existingAccount.subject");

    private final EmailComposer composer;
    private final EmailSender sender;
    private final EmailProperties properties;
    private final AsyncTaskExecutor emailExecutor;

    RegistrationEmailListener(EmailComposer composer,
                              EmailSender sender,
                              EmailProperties properties,
                              @Qualifier(EmailConfig.EMAIL_EXECUTOR) AsyncTaskExecutor emailExecutor) {
        this.composer = composer;
        this.sender = sender;
        this.properties = properties;
        // Qualified by name: Boot's applicationTaskExecutor is also an
        // AsyncTaskExecutor, so by type this is ambiguous -- and the wrong one
        // is the unbounded pool that serves the web layer.
        this.emailExecutor = emailExecutor;
    }

    /**
     * One listener for both events, so that neither registration outcome can
     * acquire a send path -- or a send latency -- the other does not have.
     *
     * <p>Runs on the thread that committed, and does one thing on it: hand the
     * work to the mail pool. Rendering and sending happen on {@code email-1}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationEmail(RegistrationEmailEvent event) {
        try {
            emailExecutor.execute(() -> sendNow(event));
        } catch (TaskRejectedException queueFull) {
            // The mail pool is saturated: fifty sends are already waiting, so
            // the provider has been slow or down for a while. This email is
            // dropped rather than queued, which is what the bounded queue is
            // for -- and the point of catching it here rather than letting it
            // escape is that this line names the account. Escaping, it reaches
            // TransactionSynchronizationUtils and becomes an anonymous stack
            // trace.
            //
            // Not rethrown: the registration committed and is correct. Failing
            // the response now would tell the user their account does not
            // exist when it does.
            log.error("mail queue full, no {} email sent for account {}",
                    event.getClass().getSimpleName(), event.userId(), queueFull);
        }
    }

    /**
     * On the mail pool. Catches everything, because this is the end of the line:
     * there is no caller left to handle anything, and an exception escaping here
     * goes to the executor's own handler, which logs at a level and in a format
     * nobody chose for the one failure whose only symptom is an email that
     * never arrives.
     */
    private void sendNow(RegistrationEmailEvent event) {
        try {
            sender.send(compose(event));
            log.info("sent the {} email for account {}",
                    event.getClass().getSimpleName(), event.userId());
        } catch (RuntimeException failed) {
            // The id, never the event: a Verification carries the token, and a
            // token in a log file is a verification somebody else can perform.
            log.error("failed to send the registration email for account {}", event.userId(), failed);
        }
    }

    private EmailMessage compose(RegistrationEmailEvent event) {
        return switch (event) {
            case RegistrationEmailEvent.Verification verification -> composer.compose(
                    verification.email(),
                    verification.locale(),
                    VERIFICATION,
                    Map.of("displayName", verification.displayName(),
                            "verificationUrl", verificationUrl(verification.token()),
                            // From the enum rather than written again as a
                            // literal in two message bundles: the seven days in
                            // the email is the token's lifetime, and the day
                            // those disagree the email is the one that is wrong.
                            "expiryDays", TokenPurpose.EMAIL_VERIFICATION.ttl().toDays()));
            case RegistrationEmailEvent.ExistingAccount existing -> composer.compose(
                    existing.email(),
                    existing.locale(),
                    EXISTING_ACCOUNT,
                    Map.of("displayName", existing.displayName(),
                            "loginUrl", url(LOGIN_PATH)));
        };
    }

    /**
     * {@code UriComponentsBuilder} rather than string concatenation, so the
     * token is percent-encoded by something that knows the rules. Today's
     * tokens are digits, dots and Base64url, all of which survive a query
     * string untouched -- but that is a property of the current token format,
     * and the day it changes is not the day to discover this was hand-rolled.
     */
    private String verificationUrl(String token) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path(VERIFY_PATH)
                .queryParam(TOKEN_PARAMETER, token)
                .build()
                .encode()
                .toUriString();
    }

    private String url(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path).build().encode().toUriString();
    }
}
