package com.fivegoodmins.fivegoodmins.shared;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
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
