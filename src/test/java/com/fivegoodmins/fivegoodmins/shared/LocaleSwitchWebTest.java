package com.fivegoodmins.fivegoodmins.shared;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The language switcher (ADR-008), over a real page.
 *
 * <p>{@code LocaleConfigTest} covers the resolver as an object. What it cannot
 * cover is the half that only exists once there is a page: the interceptor
 * reaching the resolver through a door {@code parseLocaleValue} never sees,
 * and the links the layout renders.
 *
 * <p>Registration is the page under test only because it is the page that
 * exists. Nothing here is about registration.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LocaleSwitchWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final String BULGARIAN_HEADING = "Създайте профил";
    private static final String ENGLISH_HEADING = "Create an account";

    MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void answersABulgarianBrowserInBulgarianWithNobodyClickingAnything() throws Exception {
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BULGARIAN_HEADING)));
    }

    @Test
    void answersAnEnglishBrowserInEnglish() throws Exception {
        mockMvc.perform(get(AuthPaths.REGISTER).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ENGLISH_HEADING)));
    }

    @Test
    void matchesOnTheLanguageRatherThanTheWholeTag() throws Exception {
        // en-GB and en-US have no bundle of their own. Matching on equality
        // would drop both to Bulgarian, which is the wrong answer for most of
        // the English-speaking world.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(Locale.forLanguageTag("en-GB")))
                .andExpect(content().string(containsString(ENGLISH_HEADING)));
    }

    @Test
    void switchesLanguageOnTheQueryParameterAndRemembersItInACookie() throws Exception {
        // A Bulgarian browser asking for English: the cookie is what makes the
        // next request English too, which is the whole reason the resolver is
        // cookie-backed rather than header-only.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ENGLISH_HEADING)))
                .andExpect(cookie().value(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "en"))
                // Lax, because every emailed link is a cross-site navigation
                // (ADR-005) and under Strict a reader following one arrives in
                // the wrong language.
                .andExpect(cookie().sameSite(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "Lax"));
    }

    @Test
    void aLanguageWithNoBundleIsBulgarianRatherThanA500() throws Exception {
        // The hole wiring the interceptor opened. LocaleChangeInterceptor calls
        // setLocale directly, so without the clamp there, ?lang=de set German,
        // wrote a de cookie, and threw NoSuchMessageException on the first
        // label -- a 500 reachable from the address bar.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BULGARIAN_HEADING)));
    }

    @Test
    void somethingThatIsNotALanguageTagAtAllIsAlsoNotA500() throws Exception {
        // ignoreInvalidLocale. Without it StringUtils.parseLocale throws out of
        // preHandle, which never reaches a controller at all.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).param("lang", "../../etc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BULGARIAN_HEADING)));
    }

    @Test
    void aStaleOrForgedCookieIsBulgarianRatherThanA500() throws Exception {
        // The other door: a hand-edited browser store, or a cookie left over
        // from a future version that dropped a language again.
        mockMvc.perform(get(AuthPaths.REGISTER)
                        .locale(BULGARIAN)
                        .cookie(new jakarta.servlet.http.Cookie(
                                CookieLocaleResolver.DEFAULT_COOKIE_NAME, "de")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BULGARIAN_HEADING)));
    }

    @Test
    void theSwitcherPointsAtThePageTheReaderIsOnRatherThanAtTheHomePage() throws Exception {
        // The query string is written into the URL rather than added with
        // .param(), and the difference is not cosmetic: .param() populates the
        // parameter map and leaves getQueryString() null, which is the method
        // the switcher reads. A test built the other way passes while the
        // links silently drop everything after the "?".
        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL + "?token=abc").locale(BULGARIAN))
                .andExpect(status().isOk())
                // Both the path and the existing query survive, which is what
                // keeps a switch from throwing away what the reader was
                // looking at. & is escaped as &amp; in an href attribute.
                .andExpect(content().string(containsString("/verify-email?token=abc&amp;lang=en")));
    }

    /**
     * The query string is forced onto the request rather than written into the
     * URL, and that distinction is the whole test.
     *
     * <p>{@code get("/register?t=abc==")} does not reproduce this:
     * {@code MockHttpServletRequestBuilder} runs the template through
     * {@code UriComponentsBuilder}, which normalises the query before the
     * request object exists, so {@code getQueryString()} arrives already
     * acceptable and the test is green whatever the code does. Measured, by
     * writing it that way first.
     */
    private static RequestPostProcessor rawQueryString(String query) {
        return request -> {
            request.setQueryString(query);
            return request;
        };
    }

    @Test
    void anEqualsSignInsideAParameterValueIsNotA500() throws Exception {
        // The reachable one, and it is ordinary rather than exotic: base64
        // padding. Tomcat passes it through with a 200 because RFC 3986 allows
        // = in a query; UriComponentsBuilder.query() then splits on [^&=]+ and
        // verifies each value as QUERY_PARAM, which forbids = because that is
        // its own delimiter. Unguarded, build(true) throws out of a
        // @ModelAttribute method and the page is a 500.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).with(rawQueryString("t=abc==")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BULGARIAN_HEADING)));
    }

    @Test
    void aNestedUrlInAParameterValueIsNotA500() throws Exception {
        // The same mechanism wearing the shape it will actually arrive in once
        // anything on this site takes a return address.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN)
                        .with(rawQueryString("next=http://x/?a=b")))
                .andExpect(status().isOk());
    }

    @Test
    void anUnrebuildableQueryStillLeavesAWorkingSwitcher() throws Exception {
        // Degraded, not broken: the query is dropped because it cannot be
        // rebuilt safely, and the link still switches the language of this
        // path. Asserting this is what stops the fix being a silent catch that
        // returns null or "".
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).with(rawQueryString("t=abc==")))
                .andExpect(content().string(containsString("/register?lang=en")));
    }

    @Test
    void aMalformedPercentSequenceIsNotA500Either() throws Exception {
        // Defence in depth rather than a reachable path: Tomcat 11 answers 400
        // to this one itself, as it does to a raw space, |, {, ^, \, " and <
        // (SecurityHeadersTest measures both ends). It is kept because the
        // guard must not depend on the container's strictness -- relaxedQueryChars,
        // a different container, or a future forwarded-request path would all
        // hand this straight to the advice.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).with(rawQueryString("x=100%")))
                .andExpect(status().isOk());
    }

    @Test
    void anOrdinaryRepeatedParameterIsLeftAlone() throws Exception {
        // The guard must not swallow queries that are perfectly rebuildable --
        // a catch that fired on everything would pass the tests above while
        // quietly throwing away every reader's filters.
        mockMvc.perform(get(AuthPaths.REGISTER).locale(BULGARIAN).with(rawQueryString("a=b&a=c")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("a=b&amp;a=c&amp;lang=en")));
    }

    @Test
    void doesNotLeakItsModelAttributeIntoARedirectUrl() throws Exception {
        // The hazard of adding a model attribute to every controller in the
        // application. Spring drops the default model on a redirect --
        // ignoreDefaultModelOnRedirect has been true by default since Spring 6
        // -- so the Location header is the path and nothing else. That is a
        // framework default this advice depends on rather than sets, and if it
        // ever flips, every redirect on the site grows a ?languageLinks=
        // parameter.
        mockMvc.perform(get(AuthPaths.CHECK_EMAIL).locale(BULGARIAN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(AuthPaths.REGISTER));
    }

    @Test
    void switchingTwiceDoesNotStackTheParameter() throws Exception {
        // replaceQueryParam rather than queryParam. Appending would make lang a
        // two-valued parameter whose first entry the interceptor reads, so the
        // second click would silently do nothing.
        mockMvc.perform(get(AuthPaths.REGISTER + "?lang=en").locale(BULGARIAN))
                .andExpect(content().string(containsString("/register?lang=bg")))
                .andExpect(content().string(not(containsString("lang=en&amp;lang=bg"))));
    }
}
