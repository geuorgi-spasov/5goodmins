package com.fivegoodmins.fivegoodmins.shared.email;

/**
 * One email's three resources, named together because they change together.
 *
 * <p>A template, its plain-text twin, and the bundle key for its subject are
 * not three independent facts: adding an email means adding all three, and
 * renaming one without the others produces either a {@code TemplateInputException}
 * or a subject reading {@code ??email.x.subject_bg??} in somebody's inbox.
 * Pairing them in one value means a caller names the email once.
 *
 * @param name      the HTML template, relative to {@code classpath:/templates/}
 *                  and without the {@code .html} suffix -- for example
 *                  {@code email/verification}. The plain-text part is the same
 *                  name with {@code .txt}, which is a convention rather than a
 *                  second parameter so that the two halves of one email cannot
 *                  drift apart.
 * @param subjectKey the key in messages_bg / messages_en (ADR-008). Resolved
 *                  with the recipient's stored locale, never with the server's.
 */
public record EmailTemplate(String name, String subjectKey) {

    /** The plain-text template name. See {@link #name}. */
    String textName() {
        return name + ".txt";
    }
}
