package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page 7 of the frontend brief, and the authentication it is the front of.
 *
 * <p>The round trip at the bottom is the point of the file. {@code loginPage()}
 * naming a controller has three consequences that are easy to get individually
 * right and collectively wrong: Spring Security's generated page stops
 * answering, the path has to be permitted explicitly, and the failure redirect
 * becomes {@code /login?error}. A test of the template alone would pass with
 * the filter chain pointing somewhere else entirely.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LoginWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final String PASSWORD = "correct horse battery staple";

    MockMvc mockMvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
    }

    private AppUser account(String email, boolean verified) {
        AppUser user = new AppUser(
                AppUser.normalizeEmail(email), passwordEncoder.encode(PASSWORD), "Иван", Instant.now());
        if (verified) {
            user.markEmailVerified(Instant.now());
        }
        return users.saveAndFlush(user);
    }

    @Test
    void servesTheFormWithTheFieldNamesTheFilterChainReads() throws Exception {
        mockMvc.perform(get(AuthPaths.LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Вход в профила")))
                // username, not email: it is what
                // UsernamePasswordAuthenticationFilter reads. Only the label
                // says "email address".
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void saysOneVagueThingAboutEveryKindOfFailure() throws Exception {
        mockMvc.perform(get(AuthPaths.LOGIN).param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Грешен имейл адрес или парола.")));
    }

    @Test
    void putsNothingFromTheQueryStringOnThePage() throws Exception {
        // The parameter is read for its presence, never for its value. Read
        // for its value it would be a way to put chosen text -- "your account
        // has been suspended, call this number" -- on a page carrying the
        // site's own branding.
        mockMvc.perform(get(AuthPaths.LOGIN).param("error", "your account was suspended"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("suspended"))));
    }

    @Test
    void treatsSigningOutAsAnOutcomeRatherThanAnError() throws Exception {
        mockMvc.perform(get(AuthPaths.LOGIN).param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Излязохте от профила си.")))
                .andExpect(content().string(not(containsString("Грешен имейл адрес"))));
    }

    @Test
    void logsInAConfirmedAccount() throws Exception {
        account("ivan@example.com", true);

        mockMvc.perform(post(AuthPaths.LOGIN).with(csrf())
                        .param("username", "ivan@example.com")
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("ivan@example.com"));
    }

    @Test
    void refusesAnAccountWhoseAddressWasNeverConfirmed() throws Exception {
        // ADR-002's gate, from the outside. It lives in
        // AppUserDetailsService above Spring Security's own status checks, so
        // that an unverified account is indistinguishable from an unknown one
        // -- and the page below cannot tell them apart either.
        account("nikola@example.com", false);

        mockMvc.perform(post(AuthPaths.LOGIN).with(csrf())
                        .param("username", "nikola@example.com")
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl(AuthPaths.LOGIN + "?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void answersAnUnknownAddressTheSameWayAsAnUnconfirmedOne() throws Exception {
        mockMvc.perform(post(AuthPaths.LOGIN).with(csrf())
                        .param("username", "nobody@example.com")
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl(AuthPaths.LOGIN + "?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void refusesTheRightAddressWithTheWrongPassword() throws Exception {
        account("ivan@example.com", true);

        mockMvc.perform(post(AuthPaths.LOGIN).with(csrf())
                        .param("username", "ivan@example.com")
                        .param("password", "not the password"))
                .andExpect(redirectedUrl(AuthPaths.LOGIN + "?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void refusesALoginWithoutACsrfToken() throws Exception {
        // The default that is easy to disable while chasing a 403 from an htmx
        // POST. Without the token this is a 403 rather than a login.
        account("ivan@example.com", true);

        mockMvc.perform(post(AuthPaths.LOGIN)
                        .param("username", "ivan@example.com")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(unauthenticated());
    }
}
