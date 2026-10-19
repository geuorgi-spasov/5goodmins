package com.fivegoodmins.fivegoodmins.shared.email;

/**
 * A message that was not handed to the transport.
 *
 * <p>Unchecked, because no caller can usefully handle it. The listener that
 * sends logs it and stops; there is nothing to retry against (the provider is
 * down or the configuration is wrong, and both outlive one request), nothing to
 * roll back (the account exists and should), and no queue to park it in -- a
 * retry table is a feature ADR-005 does not have.
 *
 * <p>One type for every transport, so the listener catches the failure of the
 * send rather than {@code MailException} from one implementation and whatever
 * an HTTP client throws from the next.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
