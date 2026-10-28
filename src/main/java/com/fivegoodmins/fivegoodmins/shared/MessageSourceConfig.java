package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;

/**
 * Where every user-facing string comes from (ADR-008).
 *
 * <p><strong>Declared rather than left to Boot, because Boot's version does
 * not appear for this project's bundle layout.</strong>
 * {@code MessageSourceAutoConfiguration} is behind a condition that resolves
 * {@code classpath*:messages.properties} and matches only if that exact
 * resource exists. ADR-008 ships {@code messages_bg.properties} and
 * {@code messages_en.properties} and deliberately no default-locale bundle, so
 * the condition does not match, no {@code MessageSource} is created, and the
 * context falls back to a {@code DelegatingMessageSource} that resolves
 * nothing. The symptom is not a startup failure: it is
 * {@code NoSuchMessageException} at the moment an email is rendered, on a pool
 * thread, hours later. Measured, by the email tests failing.
 *
 * <p>The alternative was an empty {@code messages.properties} to satisfy the
 * condition. Rejected: a file that exists only to make a condition true, whose
 * emptiness is load-bearing and whose comment would be longer than this class.
 *
 * <p>Boot's auto-configuration is {@code @ConditionalOnMissingBean(name =
 * "messageSource")}, so this replaces it deterministically rather than racing
 * it. The cost is that {@code spring.messages.*} is no longer read; the two
 * settings that matter are stated below, where their reasons fit.
 */
@Configuration(proxyBeanMethods = false)
class MessageSourceConfig {

    /** {@code messages_bg.properties} / {@code messages_en.properties}. */
    private static final String BASENAME = "messages";

    @Bean(AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    MessageSource messageSource() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename(BASENAME);

        // UTF-8, or every Cyrillic label in the application is mojibake. Not a
        // default worth relying on: the JDK read .properties as ISO-8859-1
        // until Java 9, and most of what is written about this still assumes
        // that -- which is why the bundles carry a comment saying not to
        // "fix" an encoding problem by escaping the Bulgarian.
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());

        // No fallback to the server's locale, and there is no default-locale
        // bundle to fall back to either. Both bundles are complete, and the
        // locales that reach this come from app_user.locale, which
        // ck_app_user_locale restricts to 'bg' and 'en'. So an unresolvable
        // code is a missing key rather than an unusual browser, and it should
        // throw where a test can see it instead of quietly rendering whatever
        // language the host happens to be set to -- which on a Railway
        // container is not a decision anybody made.
        messages.setFallbackToSystemLocale(false);

        // Nothing else. useCodeAsDefaultMessage stays off, so a missing key is
        // a NoSuchMessageException rather than the literal "email.verification.subject"
        // in somebody's inbox.
        return messages;
    }
}
