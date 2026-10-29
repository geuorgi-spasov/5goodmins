package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Locale;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-002's rate limiting, through the filter chain it actually sits in.
 *
 * <p><strong>Its own context, with numbers of its own.</strong> The suite runs
 * with {@code app.rate-limit.enabled=false} -- see
 * {@code src/test/resources/application.properties} for why -- so this class
 * turns it back on, and uses small limits so that a test is four requests
 * rather than twenty-one.
 *
 * <p><strong>Every test uses a client address and an email of its own, and
 * that is load-bearing.</strong> Buckets are process state in a singleton, not
 * rows: {@code @Transactional} rolls back the registrations but not the
 * allowance they spent, and the context is shared across methods. Two tests
 * sharing one address would pass or fail depending on execution order, which
 * is the worst kind of flake. {@code @DirtiesContext} would fix it by
 * restarting Spring between methods, which is a Testcontainers-backed context
 * rebuilt five times to avoid writing five different addresses.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login-attempts=3",
        "app.rate-limit.registrations-per-address=4",
        "app.rate-limit.registrations-per-email=2"})
@Import(TestcontainersConfiguration.class)
@Transactional
class RateLimitWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
    }

    @Test
    void refusesTheFourthLoginAttemptFromOneClient() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginAttempt("203.0.113.11"))
                    .andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(loginAttempt("203.0.113.11"))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * MockMvc does not model Tomcat's error dispatch, so this pins our half of
     * the contract -- the header is set before {@code sendError} -- and
     * {@code RateLimitHttpTest} measures whether it survives a real server.
     */
    @Test
    void tellsTheClientHowLongToWait() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginAttempt("203.0.113.12"));
        }

        mockMvc.perform(loginAttempt("203.0.113.12"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void doesNotChargeOneClientForAnothersAttempts() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(loginAttempt("203.0.113.13"));
        }

        mockMvc.perform(loginAttempt("198.51.100.13"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Reading the login page is not an attempt. If this ever fails, the limiter
     * has started counting page views and every visitor behind one NAT address
     * locks the others out of the form.
     */
    @Test
    void neverLimitsReadingTheLoginPage() throws Exception {
        for (int view = 1; view <= 10; view++) {
            mockMvc.perform(get(AuthPaths.LOGIN).with(from("203.0.113.14")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void refusesTheFifthRegistrationFromOneClient() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(registration("203.0.113.15", "nova" + attempt + "@example.invalid"))
                    .andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(registration("203.0.113.15", "nova5@example.invalid"))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * The bucket that protects somebody else's inbox rather than this server
     * (ADR-002): {@code register} mails the owner of an unverified address once
     * per request, so the cap has to follow the address across clients. Three
     * different addresses here, all under the per-client limit of four.
     */
    @Test
    void refusesAThirdRegistrationOfOneAddressEvenFromADifferentClient() throws Exception {
        String target = "cel@example.invalid";

        mockMvc.perform(registration("203.0.113.16", target))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(registration("198.51.100.16", target))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(registration("192.0.2.16", target))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * Case is not a fresh bucket. The service normalizes the address before it
     * keys an account on it, so anything else here would be a bypass spelled
     * with a capital letter.
     */
    @Test
    void treatsTheSameAddressInADifferentCaseAsTheSameAddress() throws Exception {
        mockMvc.perform(registration("203.0.113.17", "Maya@Example.Invalid"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(registration("203.0.113.17", "maya@example.invalid"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(registration("203.0.113.17", "  MAYA@EXAMPLE.INVALID  "))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * The consequence of sitting in front of {@code CsrfFilter} rather than
     * behind it: a spray with no token is still counted and still refused,
     * rather than costing a session lookup each time. Asserted because it is
     * the visible half of a filter-ordering decision, and a later reshuffle
     * would otherwise change it silently.
     */
    @Test
    void countsAttemptsThatNeverGetPastCsrf() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post(AuthPaths.LOGIN)
                            .param("username", "ivan@example.invalid")
                            .param("password", "whatever")
                            .with(from("203.0.113.18")))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(post(AuthPaths.LOGIN)
                        .param("username", "ivan@example.invalid")
                        .param("password", "whatever")
                        .with(from("203.0.113.18")))
                .andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder loginAttempt(String client) {
        return post(AuthPaths.LOGIN)
                .param("username", "ivan@example.invalid")
                .param("password", "wrong password")
                .with(csrf())
                .with(from(client));
    }

    private static MockHttpServletRequestBuilder registration(String client, String email) {
        return post(AuthPaths.REGISTER)
                .param("email", email)
                .param("displayName", "Иван")
                .param("password", "correct horse battery staple")
                .param("passwordConfirmation", "correct horse battery staple")
                .param("privacyAccepted", "true")
                .with(csrf())
                .with(from(client));
    }

    /**
     * MockMvc's remote address is {@code 127.0.0.1} unless something sets it,
     * so without this every test in the file would share one bucket.
     */
    private static RequestPostProcessor from(String client) {
        return request -> {
            request.setRemoteAddr(client);
            return request;
        };
    }
}
