package com.fivegoodmins.fivegoodmins.shared.email;

/**
 * One rendered email, ready to hand to a transport.
 *
 * <p>Values rather than a template name and a model, and that is the point:
 * rendering happens where the request's data still makes sense ({@link
 * EmailComposer}), and what crosses into the transport is text. It can be
 * asserted on in a test without a mail server and logged without a persistence
 * context.
 *
 * <p><strong>Both bodies, always.</strong> A multipart/alternative message with
 * a plain-text part is not politeness: a mail client that renders only text
 * otherwise shows nothing, and an HTML-only message scores worse with spam
 * filters -- which matters for a new sending domain whose reputation is the
 * thing standing between the digest and the junk folder (ADR-005).
 *
 * @param to      a single recipient, already normalized and lowercased, read
 *                from {@code app_user.email}. One address per message: this
 *                application never emails two people the same message, and a
 *                list would be a second code path for a case that does not
 *                exist.
 * @param subject from the message bundle (ADR-008), and <strong>never
 *                containing user-supplied text</strong>. A display name with a
 *                newline in it would be header injection -- the subject is the
 *                one field in a message where a line break ends the field and
 *                starts a new header. User-supplied text belongs in the body,
 *                where it is escaped by the template engine.
 * @param html    the rendered HTML part
 * @param text    the rendered plain-text part
 */
public record EmailMessage(String to, String subject, String html, String text) {

    public EmailMessage {
        requireText(to, "to");
        requireText(subject, "subject");
        requireText(html, "html");
        requireText(text, "text");
        if (subject.indexOf('\n') >= 0 || subject.indexOf('\r') >= 0) {
            // Stated here rather than trusted to the transport. MimeMessageHelper
            // does encode the subject, so this is a second line of defence, and
            // it is cheap: it turns a class of bug into a startup-style failure
            // at the one place every outgoing message passes through.
            throw new IllegalArgumentException("subject contains a line break");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
