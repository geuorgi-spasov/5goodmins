package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * One message carried across a redirect, for the flash area in the layout
 * (frontend brief, sections 5 and 6).
 *
 * <p><strong>A message key, never a message.</strong> The text is resolved in
 * the template, which is the only place that knows the request locale --
 * resolving it here would mean a controller holding a {@code MessageSource}
 * and the bundle rule (ADR-008) surviving on everyone remembering it.
 *
 * <p>Flash scope, so the message survives exactly one redirect and is gone
 * from the session afterwards. Putting it in the normal model instead would
 * lose it at the redirect; putting it in the session by hand would leave it
 * to be shown twice.
 *
 * @param level      which of the three the layout renders; also the message
 *                   key for the screen-reader label, as {@code flash.<level>.label}
 * @param messageKey the bundle key of the text itself
 * @param arguments  MessageFormat arguments for that key, usually none
 */
public record FlashMessage(Level level, String messageKey, Object... arguments) {

    /** The three the design system has to cover. */
    public enum Level {
        SUCCESS, ERROR, INFO;

        /** Lowercased for the key and for a CSS class, so neither is built in the template. */
        public String key() {
            return name().toLowerCase();
        }
    }

    /**
     * The model attribute name the layout's flash fragment reads. One
     * constant, because a typo on either side is a message that silently
     * never appears.
     */
    public static final String ATTRIBUTE = "flash";

    public static void success(RedirectAttributes attributes, String messageKey, Object... arguments) {
        add(attributes, new FlashMessage(Level.SUCCESS, messageKey, arguments));
    }

    public static void error(RedirectAttributes attributes, String messageKey, Object... arguments) {
        add(attributes, new FlashMessage(Level.ERROR, messageKey, arguments));
    }

    public static void info(RedirectAttributes attributes, String messageKey, Object... arguments) {
        add(attributes, new FlashMessage(Level.INFO, messageKey, arguments));
    }

    private static void add(RedirectAttributes attributes, FlashMessage message) {
        attributes.addFlashAttribute(ATTRIBUTE, message);
    }
}
