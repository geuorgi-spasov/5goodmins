package com.fivegoodmins.fivegoodmins.shared;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clamp, which is load-bearing rather than defensive.
 *
 * <p>{@code MessageSourceConfig} makes an unresolvable message code throw, with
 * no fallback bundle and no fallback to the server's locale. That is the right
 * trade -- a missing key should fail where a test can see it rather than
 * silently render in whatever language a Railway container defaults to -- but
 * it only holds if nothing can ask for a third language. These are the two ways
 * something could: the header a browser sends, and a cookie anybody can edit.
 *
 * <p>No Spring context: the resolver is a plain object and
 * {@code MockHttpServletRequest} is enough. A {@code @SpringBootTest} here
 * would cost ten seconds to test a method.
 */
class LocaleConfigTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final Locale ENGLISH = Locale.forLanguageTag("en");

    private final LocaleResolver resolver = new LocaleConfig().localeResolver();

    @Test
    void honoursABrowserThatAsksForOneOfTheTwoLanguages() {
        assertThat(resolve(request().withAcceptLanguage(ENGLISH))).isEqualTo(ENGLISH);
        assertThat(resolve(request().withAcceptLanguage(BULGARIAN))).isEqualTo(BULGARIAN);
    }

    @Test
    void treatsARegionalEnglishAsEnglish() {
        // The case a hand-written string comparison gets wrong, and the most
        // common Accept-Language value in the world.
        assertThat(resolve(request().withAcceptLanguage(Locale.UK))).isEqualTo(ENGLISH);
        assertThat(resolve(request().withAcceptLanguage(Locale.US))).isEqualTo(ENGLISH);
    }

    @Test
    void fallsBackToBulgarianForALanguageThisSiteDoesNotHave() {
        // Without this the home page is a NoSuchMessageException for anyone
        // whose browser is set to German -- which is the whole reason the
        // resolver is declared rather than left to Boot's default.
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN))).isEqualTo(BULGARIAN);

        // This also covers the request that sends no Accept-Language at all:
        // the servlet container substitutes its own default locale, which on a
        // Railway container is whatever the base image decided, so the clamp is
        // what decides rather than the host.
        assertThat(resolve(request().withAcceptLanguage(Locale.JAPANESE))).isEqualTo(BULGARIAN);
    }

    @Test
    void prefersTheNextSupportedLanguageOverTheFirstUnsupportedOne() {
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN, Locale.FRENCH, ENGLISH)))
                .isEqualTo(ENGLISH);
    }

    @Test
    void letsTheCookieOverrideTheHeader() {
        // The switcher's whole purpose: a reader on a German-configured
        // machine who wants the site in English.
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN).withLocaleCookie("en")))
                .isEqualTo(ENGLISH);
    }

    @Test
    void ignoresACookieNamingALanguageThisSiteDoesNotHave() {
        // A cookie is user-supplied input. Hand-edited to `de`, or left over
        // from a version that had a third language: either way it must not
        // reach the MessageSource. The header here is German too, so what is
        // being asserted is the final fallback rather than the header rescuing
        // the request.
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN).withLocaleCookie("de")))
                .isEqualTo(BULGARIAN);
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN).withLocaleCookie("not-a-language-tag")))
                .isEqualTo(BULGARIAN);
        assertThat(resolve(request().withAcceptLanguage(Locale.GERMAN).withLocaleCookie("")))
                .isEqualTo(BULGARIAN);
    }

    @Test
    void stillHonoursTheHeaderWhenTheCookieIsUnusable() {
        // An unusable cookie is not a reason to ignore a perfectly good header:
        // the cookie is discarded and the request is treated as a first visit.
        assertThat(resolve(request().withAcceptLanguage(ENGLISH).withLocaleCookie("de")))
                .isEqualTo(ENGLISH);
    }

    private Locale resolve(MockHttpServletRequest request) {
        return resolver.resolveLocale(request);
    }

    private static Request request() {
        return new Request();
    }

    /** MockHttpServletRequest with the two inputs under test, readably. */
    private static final class Request extends MockHttpServletRequest {

        Request withAcceptLanguage(Locale... locales) {
            setPreferredLocales(java.util.List.of(locales));
            return this;
        }

        Request withLocaleCookie(String value) {
            setCookies(new Cookie(CookieLocaleResolver.DEFAULT_COOKIE_NAME, value));
            return this;
        }
    }
}
