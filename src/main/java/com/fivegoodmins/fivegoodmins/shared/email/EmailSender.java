package com.fivegoodmins.fivegoodmins.shared.email;

/**
 * The one way out of this application for an email (ADR-005).
 *
 * <p>Production sends through a transactional provider's HTTP API; development
 * sends to Mailpit over SMTP. Without this interface those are two different
 * code paths and the one that gets exercised daily is not the one that ships.
 * With it they differ in exactly one class, and the same reasoning as
 * {@code ImageStorage} in ADR-006 applies: a named, concrete migration path,
 * not a speculative abstraction. The provider is still an open choice (§5), so
 * the class that will implement this for production does not exist yet.
 *
 * <p><strong>Implementations must be safe to call from a pool thread and must
 * not be given a persistent entity.</strong> Every send happens in an
 * {@code @Async} {@code @TransactionalEventListener(AFTER_COMMIT)}, so the
 * transaction is closed and the persistence context is gone by the time this
 * is reached -- hence {@link EmailMessage}, which is four strings.
 *
 * <p><strong>Failure is an exception, not a return value.</strong> A send that
 * did not happen must not be mistakable for one that did: there is no
 * {@code boolean} to ignore. The caller's job is to log it loudly, because
 * nothing rolls back and, with no resend form (ADR-002), the user's only
 * recovery is to register again.
 *
 * <p>Tests use a recording implementation. Nothing in the test suite points at
 * Mailpit and no test sends real mail.
 */
public interface EmailSender {

    /**
     * @throws EmailDeliveryException if the message could not be handed to the
     *                                transport
     */
    void send(EmailMessage message);
}
