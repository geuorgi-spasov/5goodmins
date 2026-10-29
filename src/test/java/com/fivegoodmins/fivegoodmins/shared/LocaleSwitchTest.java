package com.fivegoodmins.fivegoodmins.shared;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final LocaleSwitch advice = new LocaleSwitch();

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

    @Test
    void offersBothLanguagesAndMarksExactlyOneAsCurrent() {
        assertThat(advice.languageLinks(request("/register", null)))
                .extracting(LocaleSwitch.LanguageLink::tag)
                .containsExactly("bg", "en");
        assertThat(advice.languageLinks(request("/register", null)))
                .filteredOn(LocaleSwitch.LanguageLink::current)
                .hasSize(1);
    }
}
