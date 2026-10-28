package com.fivegoodmins.fivegoodmins.shared.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends over SMTP. In development that is Mailpit in Docker Compose (ADR-005).
 *
 * <p>Mailpit rather than logging the body to the console, because the
 * deliverable is HTML email that has to survive Gmail, Outlook and Apple Mail:
 * a logged string cannot be looked at, and Mailpit renders the HTML, the
 * plain-text alternative and the headers side by side. No test points at it.
 *
 * <p>Thin on purpose, and therefore untested: it builds a
 * {@code multipart/alternative} message and hands it over. There is no branch
 * in here worth a test that a mock {@code JavaMailSender} would not simply be
 * asserting against itself, and the part that can really go wrong -- whether a
 * mail client renders the result -- is answered by looking at Mailpit.
 */
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender transport;
    private final EmailProperties properties;

    SmtpEmailSender(JavaMailSender transport, EmailProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = transport.createMimeMessage();
        try {
            // true, true: multipart and UTF-8. UTF-8 matters because the
            // content is Bulgarian and the default is the platform encoding,
            // which on one host renders Cyrillic and on another renders
            // question marks.
            //
            // What `true` actually produces, verified against Mailpit rather
            // than assumed, is multipart/mixed > multipart/related >
            // multipart/alternative: the helper's default mode leaves room for
            // attachments and inline images that these emails do not have. A
            // bare multipart/alternative is the textbook structure and would
            // mean building the MimeMultipart by hand, so the nesting stays --
            // every client in the Mailpit compatibility check handles it, and
            // the digest is the email that may yet want an inline image.
            MimeMessageHelper helper =
                    new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            if (properties.fromName() == null || properties.fromName().isBlank()) {
                helper.setFrom(properties.from());
            } else {
                helper.setFrom(properties.from(), properties.fromName());
            }
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            // Text first, then HTML: the argument order is (plainText, html) and
            // it decides which part a client picks. MIME says the *last*
            // alternative is the richest, and the helper writes them in that
            // order -- reversing the arguments produces a message whose HTML is
            // the fallback, which is how an HTML email ends up showing as tags.
            helper.setText(message.text(), message.html());
            transport.send(mime);
        } catch (MessagingException | UnsupportedEncodingException | MailException failed) {
            // Logged here as well as by the caller, because this is where the
            // provider's own message is: the listener knows which user it was
            // for, this knows why the transport refused. Neither logs the body.
            log.error("SMTP transport refused a message to {}", message.to(), failed);
            throw new EmailDeliveryException("SMTP send failed", failed);
        }
    }
}
