package com.fivegoodmins.fivegoodmins.shared;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The URI-building edge cases of {@link LocaleSwitch}, as a plain object.
 *
 * <p>{@code LocaleSwitchWebTest} covers what arrives through a real filter
 * chain; this covers what {@code UriComponentsBuilder} does with it, including
 * the cases MockMvc cannot express. A malformed <em>path</em> is the clearest
 * of those: setting {@code requestURI} to {@code /a%zzb} through MockMvc stops
 * the request matching any handler, so it 404s before the advice ever runs,
 * and the method under test is never reached. Called directly, it is two lines.
 *
 * <p>No Spring context, deliberately -- the advice is a plain class and a
 * {@code @SpringBootTest} would cost ten seconds to build four strings.
 */
class LocaleSwitchTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    private final LocaleSwitch advice = new LocaleSwitch();

    /**
     * {@code LocaleContextHolder} is a thread local and Surefire reuses the JVM
     * and its threads across test classes, so a locale left set here would
     * follow whatever runs next -- masking a failure, or inventing one.
     */
    @AfterEach
    void clearLocaleContext() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static MockHttpServletRequest request(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setQueryString(query);
        return request;
    }

    private String englishLinkFor(String uri, String query) {
        return advice.languageLinks(request(uri, query)).stream()
                .filter(link -> link.tag().equals("en"))
                .findFirst()
                .orElseThrow()
                .url();
    }

    @Test
    void keepsThePathAndTheQueryWhenBothCanBeRebuilt() {
        assertThat(englishLinkFor("/register", "a=b&a=c")).isEqualTo("/register?a=b&a=c&lang=en");
    }

    @Test
    void keepsThePathWhenTheQueryCannotBeRebuilt() {
        // An = inside a value: legal in a query per RFC 3986, accepted by
        // Tomcat, refused by UriComponentsBuilder as QUERY_PARAM.
        assertThat(englishLinkFor("/register", "t=abc==")).isEqualTo("/register?lang=en");
    }

    @Test
    void fallsBackToABareQueryWhenEvenThePathCannotBeRebuilt() {
        // The bug this test exists for: the path fallback used to sit outside
        // the guard and called build(true) itself, so a path like this rethrew
        // the exception the guard had just caught -- a 500 again, by the same
        // mechanism, one line further down.
        //
        // "?lang=en" is a query-only relative reference: RFC 3986 section 5.3
        // resolves it against the current URL, so the browser keeps the path it
        // is already on and there is nothing left to encode.
        assertThat(englishLinkFor("/a%zzb", "t=abc==")).isEqualTo("?lang=en");
    }

    @Test
    void fallsBackToABareQueryOnABadPathWithNoQueryAtAll() {
        // The path that skipped the guard entirely: with query == null the old
        // code went straight to the unguarded line.
        assertThat(englishLinkFor("/a%", null)).isEqualTo("?lang=en");
    }

    /**
     * Pins the one framework behaviour {@link LocaleSwitch}'s decision to stay
     * unscoped rests on, so the reasoning in its Javadoc cannot quietly rot.
     *
     * <p>{@code @ControllerAdvice(annotations = Controller.class)} is the
     * obvious way to keep this advice off a future {@code @RestController},
     * and it excludes nothing: {@code @RestController} is itself
     * meta-annotated {@code @Controller}, and the predicate behind that
     * attribute matches through {@code AnnotatedElementUtils.hasAnnotation},
     * which searches meta-annotations. If that ever changes, this fails and
     * the scoping becomes available.
     *
     * <p>This is deliberately <em>not</em> a test that a {@code @RestController}
     * response carries {@code languageLinks}. That claim is unobservable from
     * outside: a {@code @ResponseBody} handler returns no {@code ModelAndView},
     * so proving the advice ran would need a bespoke controller written to
     * echo its own model back, registered in the application context, which
     * would fork the context cache for the whole suite to document a cost
     * already accepted. Three lines with no context prove the same thing.
     */
    @Test
    void scopingTheAdviceByControllerAnnotationWouldNotExcludeARestController() {
        assertThat(HandlerTypePredicate.forAnnotation(Controller.class).test(JsonApi.class))
                .as("@RestController is meta-annotated @Controller")
                .isTrue();
        assertThat(HandlerTypePredicate.forAnnotation(Controller.class).test(HtmlPage.class))
                .isTrue();
    }

    @RestController
    private static final class JsonApi { }

    @Controller
    private static final class HtmlPage { }

    @Test
    void offersBothLanguagesInTheOrderTheyAreConfigured() {
        // From LocaleConfig.SUPPORTED, so Bulgarian is first (ADR-008). Not
        // locale-dependent, unlike the `current` flag below.
        assertThat(advice.languageLinks(request("/register", null)))
                .extracting(LocaleSwitch.LanguageLink::tag)
                .containsExactly("bg", "en");
    }

    @Test
    void marksTheLanguageBeingReadAsCurrentAndLeavesTheOtherALink() {
        // The locale is set explicitly, and that is the fix for a real bug in
        // the first version of this test rather than ceremony. With no request
        // bound, LocaleContextHolder.getLocale() falls back to
        // Locale.getDefault() -- so a test asserting "exactly one is current"
        // passed on a bg/en machine and failed for anyone running with, say,
        // -Duser.language=de, where neither tag matches and the answer is
        // zero. Measured: it fails with "Expected size: 1 but was: 0".
        //
        // Setting it is better than loosening the assertion to "at most one",
        // because it lets this assert WHICH language is current -- which is the
        // behaviour the layout depends on to render one of them as text rather
        // than as a link that goes nowhere.
        LocaleContextHolder.setLocale(BULGARIAN);
        assertThat(advice.languageLinks(request("/register", null)))
                .extracting(LocaleSwitch.LanguageLink::tag, LocaleSwitch.LanguageLink::current)
                .containsExactly(tuple("bg", true), tuple("en", false));

        // Both directions, so the flag is known to follow the context rather
        // than to be hardcoded to the default language.
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en"));
        assertThat(advice.languageLinks(request("/register", null)))
                .extracting(LocaleSwitch.LanguageLink::tag, LocaleSwitch.LanguageLink::current)
                .containsExactly(tuple("bg", false), tuple("en", true));
    }
}
