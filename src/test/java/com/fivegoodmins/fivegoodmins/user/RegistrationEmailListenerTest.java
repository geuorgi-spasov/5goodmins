package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.email.EmailConfig;
import com.fivegoodmins.fivegoodmins.shared.email.EmailDeliveryException;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of ADR-005's "sending is an after-commit event", measured
 * rather than asserted in a comment.
 *
 * <p>Those two halves are separate bugs and are tested separately:
 * {@code AFTER_COMMIT} is why a rolled-back registration sends no link, and the
 * mail pool is why the provider's latency never reaches the thread that is
 * answering the user -- which is what keeps the registration form from becoming
 * an account-enumeration oracle again. The third thing tested here is what
 * happens when that pool is full, which is the failure mode its bounded queue
 * exists to produce.
 *
 * <p>A {@code @SpringBootTest} rather than a unit test, because what is under
 * test is wiring: transaction-bound event delivery, a proxy that dispatches to
 * a named executor, two template resolvers, and two message bundles. Every one
 * of those is a thing that works in a hand-built object graph and fails in the
 * application. The transaction is driven by a {@link TransactionTemplate} here
 * rather than by {@code @Transactional} on the test, for the one reason that
 * matters: a test-managed transaction is rolled back, and an after-commit
 * listener in a transaction that never commits does nothing at all.
 *
 * <p>No mail leaves: {@link RecordingSender} is {@code @Primary} and replaces
 * the sender the application wires. Nothing here points at Mailpit.
 */
@Import({TestcontainersConfiguration.class, RegistrationEmailListenerTest.Collaborators.class})
@SpringBootTest
class RegistrationEmailListenerTest {

    private static final String ADDRESS = "georgi@seed.example";
    private static final String NAME = "Георги";
    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final Locale ENGLISH = Locale.forLanguageTag("en");

    /** Matches the queue capacity in EmailConfig; read together with it. */
    private static final int QUEUE_DEPTH = 50;

    /** Long enough to absorb a slow CI machine; a failure here is a hang, not a flake. */
    private static final int DELIVERY_TIMEOUT_SECONDS = 10;

    /**
     * How long to wait before concluding that nothing is coming. Short, because
     * the negative assertions pay it on every run, and a send that is going to
     * happen on the wrong side of a commit happens immediately rather than late.
     */
    private static final int SILENCE_TIMEOUT_MILLIS = 500;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    RecordingSender sender;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationContext context;

    @Autowired
    @Qualifier(EmailConfig.EMAIL_EXECUTOR)
    AsyncTaskExecutor emailExecutor;

    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        transactions = new TransactionTemplate(transactionManager);
        sender.reset();
    }

    // -----------------------------------------------------------------------
    // AFTER_COMMIT: the correctness half
    // -----------------------------------------------------------------------

    @Test
    void sendsNothingUntilTheTransactionCommits() throws InterruptedException {
        transactions.executeWithoutResult(status -> {
            events.publishEvent(verification("a-token"));
            // Inside the transaction, where a send would be the bug: a link for
            // a row that is not committed yet.
            assertThat(sender.pollForSilence()).isNull();
        });

        assertThat(sender.awaitOne().to()).isEqualTo(ADDRESS);
    }

    @Test
    void sendsNothingWhenTheTransactionRollsBack() throws InterruptedException {
        transactions.executeWithoutResult(status -> {
            events.publishEvent(verification("a-token"));
            status.setRollbackOnly();
        });

        // The whole reason AFTER_COMMIT is not a refinement. A verification
        // link for a transaction that rolled back names a user id that was
        // never committed, so it resolves to nothing for its full seven days --
        // and the user holding it has no account and no way to tell.
        assertThat(sender.pollForSilence()).isNull();
    }

    // -----------------------------------------------------------------------
    // The mail pool: the timing-oracle half
    // -----------------------------------------------------------------------

    @Test
    void sendsOnTheEmailPoolRatherThanTheThreadThatPublished() throws InterruptedException {
        String publishingThread = Thread.currentThread().getName();

        transactions.executeWithoutResult(status -> events.publishEvent(verification("a-token")));
        sender.awaitOne();

        // An AFTER_COMMIT listener runs on the thread that committed, so
        // without the executor this would be the request thread waiting
        // 100-500ms for a mail provider -- on the registering path and not on
        // the refusing one, which is the enumeration oracle back again and
        // wider than the BCrypt gap that was closed to shut it.
        assertThat(sender.lastThread()).isNotEqualTo(publishingThread);
        // The mail pool specifically, not Boot's shared executor: the whole
        // point of a pool of its own is a bounded queue, and getting the
        // qualifier wrong would silently hand the work to the unbounded one.
        assertThat(sender.lastThread()).startsWith("email-");
    }

    @Test
    void keepsSendingAfterOneSendFails() throws InterruptedException {
        sender.failNextSend();

        transactions.executeWithoutResult(status -> events.publishEvent(verification("doomed")));
        transactions.executeWithoutResult(status -> events.publishEvent(verification("fine")));

        // A failed send rolls nothing back and must not take the pool with it:
        // the listener catches and logs, so the next registration still gets
        // its email. With no resend form (ADR-002), the user whose email was
        // the one that failed has to register again -- which is the cost
        // ADR-005 accepts, and the reason the log line matters.
        assertThat(sender.awaitOne().text()).contains("fine");
    }

    @Test
    void leavesTheRestOfTheApplicationItsOwnExecutor() {
        // The other half of the test above, and the reason
        // spring.task.execution.mode=force is in application.yaml. Boot's
        // applicationTaskExecutor is @ConditionalOnMissingBean(Executor.class),
        // so defining the mail pool would otherwise remove it -- and a
        // single-threaded pool with a fifty-deep queue would quietly become
        // what Spring MVC uses for async request handling. Asserted rather
        // than trusted to a comment, because deleting that one property line
        // is a change nothing else in the suite would notice.
        assertThat(context.containsBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME))
                .as("Boot's shared executor survives alongside the mail pool")
                .isTrue();
    }

    @Test
    void dropsAnEmailRatherThanTheRegistrationWhenTheQueueIsFull() throws InterruptedException {
        // Saturate the pool: one task occupying the single thread and fifty
        // filling the queue, all parked until the gate opens.
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < QUEUE_DEPTH + 1; i++) {
            emailExecutor.execute(() -> await(gate));
        }
        try {
            transactions.executeWithoutResult(status -> events.publishEvent(verification("a-token")));

            // Two assertions, and the second is the one that matters. The email
            // is gone -- that is the bounded queue working as designed, a
            // provider that has been down long enough to back up fifty sends is
            // not going to deliver the fifty-first. But the registration that
            // caused it committed and must stay committed: failing the response
            // here would tell a user their account does not exist when it does.
            //
            // The rejection is caught in the listener rather than escaping into
            // TransactionSynchronizationUtils, which is what puts the account id
            // in the log line. That is the whole reason the submit is explicit
            // instead of @Async.
            assertThat(sender.pollForSilence()).isNull();
        } finally {
            gate.countDown();
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // What the emails actually say
    // -----------------------------------------------------------------------

    @Test
    void rendersTheVerificationEmailInTheUsersOwnLanguage() throws InterruptedException {
        transactions.executeWithoutResult(status ->
                events.publishEvent(new RegistrationEmailEvent.Verification(
                        7L, ADDRESS, NAME, BULGARIAN, "the-token")));

        EmailMessage message = sender.awaitOne();

        assertThat(message.subject()).isEqualTo("Потвърдете имейл адреса си");
        assertThat(message.html())
                .contains(NAME)
                // The absolute link, built from app.mail.base-url, because an
                // after-commit listener has no request to read a host from.
                .contains("https://test.invalid/verify-email?token=the-token")
                // The shared layout really wrapped it.
                .contains("5 Good Minutes")
                .contains("5goodmins.com")
                // The expiry in the copy is the token's own lifetime, read from
                // TokenPurpose rather than typed into two message bundles.
                .contains("7 дни");
        assertThat(message.text())
                .contains(NAME)
                .contains("https://test.invalid/verify-email?token=the-token")
                // A plain-text part that is secretly HTML is the usual way this
                // goes wrong: one resolver serving both names.
                .doesNotContain("<html")
                .doesNotContain("<td");
    }

    @Test
    void rendersTheSameEmailInEnglishForAUserWhoChoseIt() throws InterruptedException {
        transactions.executeWithoutResult(status ->
                events.publishEvent(new RegistrationEmailEvent.Verification(
                        7L, ADDRESS, "Georgi", ENGLISH, "the-token")));

        EmailMessage message = sender.awaitOne();

        // The recipient's stored locale decides, not the server's default and
        // not the locale of whoever was at the keyboard (ADR-005): the digest
        // runs at night with no request at all, and this is the same code.
        assertThat(message.subject()).isEqualTo("Confirm your email address");
        assertThat(message.html()).contains("Confirm my address").contains("7 days");
        assertThat(message.text()).contains("7 days");
    }

    @Test
    void tellsTheOwnerOfAnAlreadyRegisteredAddressAndOffersLoginNotAReset()
            throws InterruptedException {
        transactions.executeWithoutResult(status ->
                events.publishEvent(new RegistrationEmailEvent.ExistingAccount(
                        7L, ADDRESS, NAME, BULGARIAN)));

        EmailMessage message = sender.awaitOne();

        assertThat(message.subject()).isEqualTo("Вече имате профил в 5 Good Minutes");
        assertThat(message.html())
                .contains("https://test.invalid/login")
                // No verification link on this path: nothing was written, no
                // token was issued, and there is nothing here to confirm.
                .doesNotContain("/verify-email");
        // Password reset is v1.1, so this email cannot offer one. The assertion
        // is here to be deleted on the day it can.
        assertThat(message.html()).doesNotContain("/reset-password");
        assertThat(message.text()).contains("https://test.invalid/login");
    }

    @Test
    void escapesADisplayNameSomebodyTypedMarkupInto() throws InterruptedException {
        transactions.executeWithoutResult(status ->
                events.publishEvent(new RegistrationEmailEvent.Verification(
                        7L, ADDRESS, "<script>alert(1)</script>", BULGARIAN, "the-token")));

        EmailMessage message = sender.awaitOne();

        // The display name is the one user-supplied value in these emails, and
        // it is the registering user's own -- so this is not about protecting
        // them. It is about the AddressTaken path, where the name in the email
        // belongs to the account's owner and the mail goes to their mailbox:
        // whatever a stranger typed must not reach it as markup. Thymeleaf's
        // th:text escapes; the assertion is that nothing switched to th:utext.
        assertThat(message.html())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
        // The subject never carries user input at all -- a newline in a name
        // would be header injection -- which EmailMessage also refuses.
        assertThat(message.subject()).doesNotContain("script");
    }

    private static RegistrationEmailEvent.Verification verification(String token) {
        return new RegistrationEmailEvent.Verification(7L, ADDRESS, NAME, BULGARIAN, token);
    }

    // -----------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        /**
         * {@code @Primary} so the listener gets this instead of the sender
         * {@code EmailConfig} wires. Without a mail host in the test
         * properties that one is the sender that refuses, so an accidental
         * real send would fail rather than connect -- but a test that asserts
         * on content needs to see the message, not the refusal.
         */
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    /**
     * Records what was sent, from whichever thread sent it.
     *
     * <p>A blocking queue rather than a list and a sleep: the assertions are
     * about work that happens on another thread, and the two questions worth
     * asking are "did it arrive" (wait, up to a generous timeout) and "did
     * nothing arrive" (wait a little, expect nothing). Both are answered by
     * {@code poll} with a timeout, which is also why this test has no
     * {@code Thread.sleep} and no retry loop.
     */
    static final class RecordingSender implements EmailSender {

        private final BlockingQueue<EmailMessage> sent = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> threads = new LinkedBlockingQueue<>();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void send(EmailMessage message) {
            if (failNext.compareAndSet(true, false)) {
                throw new EmailDeliveryException("a deliberately failed send", null);
            }
            threads.add(Thread.currentThread().getName());
            sent.add(message);
        }

        void reset() {
            sent.clear();
            threads.clear();
            failNext.set(false);
        }

        void failNextSend() {
            failNext.set(true);
        }

        /** The message, waited for. Fails the test rather than returning null. */
        EmailMessage awaitOne() throws InterruptedException {
            EmailMessage message = sent.poll(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(message).as("an email to be sent within %ss", DELIVERY_TIMEOUT_SECONDS)
                    .isNotNull();
            return message;
        }

        /** Null when nothing was sent, which is what the negative cases assert. */
        EmailMessage pollForSilence() {
            try {
                return sent.poll(SILENCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for nothing", interrupted);
            }
        }

        String lastThread() {
            List<String> seen = List.copyOf(threads);
            assertThat(seen).as("threads that sent").isNotEmpty();
            return seen.getLast();
        }
    }
}
