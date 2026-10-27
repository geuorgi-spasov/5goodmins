package com.fivegoodmins.fivegoodmins.shared;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The two bundles say the same things, and the strict {@code MessageSource}
 * can resolve all of it.
 *
 * <p><strong>This test exists because of a trade made in
 * {@link MessageSourceConfig}.</strong> Turning off locale fallback, with no
 * default-locale bundle behind it, is what keeps a missing key from silently
 * rendering in whatever language a container happens to default to. The price
 * is that a key present in Bulgarian and absent from English is no longer a
 * cosmetic gap -- it is a {@code NoSuchMessageException}, which is a 500 on
 * that page or an email that never goes out, for English users only. Nobody
 * notices that in development, where the default locale is {@code bg}.
 *
 * <p>So the completeness ADR-008 asserts is checked rather than asserted. It
 * is the cheapest test in the suite and it covers the whole i18n surface as
 * the application grows: every label added from here on is checked in both
 * languages by this file, without anybody remembering to.
 *
 * <p>No Spring context, deliberately: the configuration class is a plain
 * object, and a {@code @SpringBootTest} would cost ten seconds to compare two
 * sets of strings.
 */
class MessageBundlesTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final Locale ENGLISH = Locale.forLanguageTag("en");

    private final MessageSource messages = new MessageSourceConfig().messageSource();

    @Test
    void bothLanguagesDefineExactlyTheSameKeys() {
        Set<String> bulgarian = keysIn("messages_bg.properties");
        Set<String> english = keysIn("messages_en.properties");

        assertThat(bulgarian).as("keys missing from English").containsExactlyElementsOf(english);
        // Both directions, because the failures are different: a key missing
        // from English is a 500 for English users, and a key missing from
        // Bulgarian is one for everybody, since bg is the default.
        assertThat(english).as("keys missing from Bulgarian").containsExactlyElementsOf(bulgarian);
        assertThat(bulgarian).as("the bundles are not empty").isNotEmpty();
    }

    @Test
    void everyKeyResolvesInBothLanguages() {
        // Parity of key sets is not quite the same claim as resolvability: this
        // also exercises the MessageSource as configured, so a basename typo or
        // a bundle that stops being found fails here rather than at the first
        // page render.
        for (String key : keysIn("messages_bg.properties")) {
            assertThatNoException()
                    .as("%s resolves in Bulgarian", key)
                    .isThrownBy(() -> messages.getMessage(key, null, BULGARIAN));
            assertThatNoException()
                    .as("%s resolves in English", key)
                    .isThrownBy(() -> messages.getMessage(key, null, ENGLISH));
        }
    }

    @Test
    void readsCyrillicAsUtf8() {
        // The one assertion that would catch the encoding regression: without
        // the explicit UTF-8 in MessageSourceConfig this comes back as the
        // ISO-8859-1 reading of the same bytes, which is mojibake rather than
        // an error -- so it would ship.
        assertThat(messages.getMessage("email.verification.subject", null, BULGARIAN))
                .isEqualTo("Потвърдете имейл адреса си");
    }

    /** Sorted, so a failure lists the difference in a readable order. */
    private static Set<String> keysIn(String bundle) {
        Properties properties = new Properties();
        try (InputStream in = MessageBundlesTest.class.getClassLoader().getResourceAsStream(bundle)) {
            assertThat(in).as("%s is on the classpath", bundle).isNotNull();
            // Read as UTF-8 explicitly: Properties.load(InputStream) is
            // ISO-8859-1 by definition, and the Reader overload is the only
            // way to say otherwise. Spring's MessageSource has its own
            // defaultEncoding; this is just reading the file to list its keys.
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + bundle, unreadable);
        }
        return new TreeSet<>(properties.stringPropertyNames());
    }
}
