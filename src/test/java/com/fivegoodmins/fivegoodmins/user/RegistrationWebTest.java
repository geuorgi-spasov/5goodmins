package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.validation.BindingResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Pages 6, 7 and 8 of the frontend brief, through the real filter chain, the
 * real templates and the real message bundles.
 *
 * <p>{@code @SpringBootTest} rather than {@code @WebMvcTest}, which is the
 * slower choice made on purpose. Half of what these pages are is configuration
 * that the web slice does not load: the strict {@code MessageSource}, the
 * clamping locale resolver, the validator wired to that {@code MessageSource},
 * and the filter chain that decides an anonymous visitor may reach
 * {@code /register} at all. A slice test would have passed with all four
 * missing.
 *
 * <p>{@code RegistrationService} is the one thing mocked. Its own behaviour has
 * twenty-four tests of its own; what is under test here is the controller's
 * refusal to let the two outcomes look different.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RegistrationWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    /** Forty Cyrillic characters, which is eighty bytes: inside @Size, past BCrypt. */
    private static final String CYRILLIC_PASSPHRASE = "паролатамитрябвадаебезсъмнениетолковадълга";

    MockMvc mockMvc;

    @MockitoBean
    RegistrationService registrations;

    /**
     * Built by hand rather than through {@code @AutoConfigureMockMvc}, for one
     * reason that took a failing run to find: {@code MockHttpServletRequest}
     * sends {@code Accept-Language: en} unless told otherwise, so every page in
     * this suite rendered in English and every assertion about Bulgarian copy
     * failed. The default is the framework's, not the browser's -- a real
     * Bulgarian visitor sends {@code bg} -- and the fix belongs here rather
     * than as a {@code .locale(...)} on fifteen request builders.
     *
     * <p>That the English came out correctly is itself worth knowing: it means
     * the {@code Accept-Language} half of {@code LocaleConfig} works. It is
     * asserted deliberately in {@code LocaleSwitchWebTest} rather than
     * accidentally here.
     */
    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
    }

    private static MockHttpServletRequestBuilder validSubmission() {
        return post(AuthPaths.REGISTER).with(csrf())
                .param("email", "ivan@example.com")
                .param("displayName", "Иван")
                .param("password", "correct horse battery staple")
                .param("passwordConfirmation", "correct horse battery staple")
                .param("privacyAccepted", "true");
    }

    @Test
    void servesTheRegistrationFormToAnAnonymousVisitorInBulgarian() throws Exception {
        mockMvc.perform(get(AuthPaths.REGISTER))
                .andExpect(status().isOk())
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(content().string(containsString("Създайте профил")))
                // The hidden input Thymeleaf's Spring dialect adds to a
                // th:action form. Written as a plain action="" it would be
                // absent and every submission would be a 403 with nothing on
                // the page to explain it.
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                // Every label is tied to its input. th:field writes the id, so
                // a for= that does not match is invisible until somebody tries
                // to tap the consent text on a phone and nothing happens.
                .andExpect(content().string(containsString("<label for=\"email\"")))
                .andExpect(content().string(containsString("id=\"email\"")))
                // The consent checkbox is the exception, and this assertion is
                // the finding that produced it: Thymeleaf renders a bound
                // checkbox as id="privacyAccepted1", because a checkbox can be
                // one of many sharing a name. A hand-written
                // for="privacyAccepted" therefore dangles, and the only
                // symptom is that tapping the consent text does nothing. The
                // label wraps the input instead, so there is no for= at all.
                .andExpect(content().string(not(containsString("for=\"privacyAccepted\""))))
                // The hidden companion Thymeleaf adds to a bound checkbox:
                // without it an unticked box sends no parameter at all, and
                // "they unticked it" would be indistinguishable from "the
                // field was not on the form".
                .andExpect(content().string(containsString("name=\"_privacyAccepted\"")));
    }

    @Test
    void bothRegistrationOutcomesProduceTheSameStatusRedirectAndFlash() throws Exception {
        // The point of the whole flow. RegistrationService already equalises
        // the time the two paths take; this is the other half -- if the
        // controller ever redirects one and re-renders the other, or varies a
        // flash message, the registration form answers "does this address have
        // an account here?" for anybody who looks at the response.
        //
        // Named for exactly what it compares. Status, Location and the flash
        // map is the whole of a redirect's observable content -- there is no
        // body -- but it is NOT every response header, and claiming "identical
        // responses" invites the follow-up question this test cannot answer.
        // Set-Cookie in particular differs by session id between the two runs.
        when(registrations.register(any(), any(), any()))
                .thenReturn(new RegistrationOutcome.Registered(1L));
        MvcResult registered = mockMvc.perform(validSubmission()).andReturn();

        when(registrations.register(any(), any(), any()))
                .thenReturn(new RegistrationOutcome.AddressTaken());
        MvcResult taken = mockMvc.perform(validSubmission()).andReturn();

        assertThat(taken.getResponse().getStatus())
                .isEqualTo(registered.getResponse().getStatus());
        assertThat(taken.getResponse().getRedirectedUrl())
                .isEqualTo(registered.getResponse().getRedirectedUrl());
        assertThat((Object) taken.getFlashMap()).isEqualTo(registered.getFlashMap());
    }

    @Test
    void aValidSubmissionRedirectsRatherThanRenderingTheNoticeFromThePost() throws Exception {
        when(registrations.register(any(), any(), any()))
                .thenReturn(new RegistrationOutcome.Registered(1L));

        // Post/Redirect/Get: a rendered POST leaves the browser holding a
        // submission to replay, and replaying this one deletes and re-inserts
        // the unverified row and sends another email on every refresh.
        mockMvc.perform(validSubmission())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(AuthPaths.CHECK_EMAIL))
                .andExpect(flash().attribute(RegistrationController.REGISTERED_EMAIL, "ivan@example.com"));
    }

    @Test
    void redisplaysTheFormWithoutThePasswordWhenSomethingIsWrong() throws Exception {
        mockMvc.perform(post(AuthPaths.REGISTER).with(csrf())
                        .param("email", "not-an-address")
                        .param("displayName", "Иван")
                        .param("password", "correct horse battery staple")
                        .param("passwordConfirmation", "correct horse battery staple")
                        .param("privacyAccepted", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(
                        containsString("Това не прилича на имейл адрес.")))
                // The password must not come back in the HTML of a page that
                // is, by definition, being returned after something went
                // wrong -- into the back-forward cache and any proxy that
                // ignores no-store.
                .andExpect(content().string(
                        not(
                                containsString("correct horse battery staple"))));

        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void rejectsAPasswordThatFitsInCharactersButNotInBytes() throws Exception {
        // @Size(max = 72) counts characters and BCrypt truncates at 72 bytes.
        // Left to RegistrationService this is an IllegalArgumentException and a
        // 500 -- for Bulgarian users only, which is to say for almost
        // everybody who will use this site.
        mockMvc.perform(post(AuthPaths.REGISTER).with(csrf())
                        .param("email", "ivan@example.com")
                        .param("displayName", "Иван")
                        .param("password", CYRILLIC_PASSPHRASE)
                        .param("passwordConfirmation", CYRILLIC_PASSPHRASE)
                        .param("privacyAccepted", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(
                        containsString("Кирилските букви заемат")));

        assertThat(CYRILLIC_PASSPHRASE.length()).isLessThanOrEqualTo(72);
        assertThat(CYRILLIC_PASSPHRASE.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void saysOneThingAboutAnOverLongPasswordRatherThanTwo() throws Exception {
        // 73 Latin characters is 73 bytes: over the byte ceiling and, when
        // @Size still carried max = 72, over that too. The form rendered two
        // messages under one field, one of which said "between 8 and 72
        // characters" -- which is not the real ceiling for the users this site
        // is for. @Size now carries only min, and the byte check owns the
        // ceiling alone.
        String tooLong = "a".repeat(73);

        MvcResult result = mockMvc.perform(post(AuthPaths.REGISTER).with(csrf())
                        .param("email", "ivan@example.com")
                        .param("displayName", "Иван")
                        .param("password", tooLong)
                        .param("passwordConfirmation", tooLong)
                        .param("privacyAccepted", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrorCode(
                        "form", "password", "validation.password.tooManyBytes"))
                .andReturn();

        BindingResult binding = (BindingResult) result.getModelAndView()
                .getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "form");
        assertThat(binding.getFieldErrors("password")).hasSize(1);

        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void turnsASignedInVisitorAwayFromTheRegistrationForm() throws Exception {
        // permitAll() permits everyone, not only the anonymous. Registering a
        // second account while holding a session for the first is a state
        // nothing downstream handles.
        mockMvc.perform(get(AuthPaths.REGISTER).with(user("ivan@example.com")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(validSubmission().with(user("ivan@example.com")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void servesThePrivacyPolicyTheConsentCheckboxLinksTo() throws Exception {
        // The consent link resolved to the login redirect before page 5
        // existed, which makes the checkbox above it undefendable: the only
        // visitor who sees it is the one who cannot read what they are
        // agreeing to.
        mockMvc.perform(get(AuthPaths.PRIVACY))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Политика за поверителност")));
    }

    @Test
    void rejectsAConfirmationThatDoesNotMatchOntoTheConfirmationField() throws Exception {
        mockMvc.perform(post(AuthPaths.REGISTER).with(csrf())
                        .param("email", "ivan@example.com")
                        .param("displayName", "Иван")
                        .param("password", "correct horse battery staple")
                        .param("passwordConfirmation", "correct horse battery stapel")
                        .param("privacyAccepted", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "passwordConfirmation"))
                .andExpect(content().string(
                        containsString("Двете пароли не съвпадат.")));

        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void refusesToRegisterWithoutConsentToThePrivacyPolicy() throws Exception {
        mockMvc.perform(post(AuthPaths.REGISTER).with(csrf())
                        .param("email", "ivan@example.com")
                        .param("displayName", "Иван")
                        .param("password", "correct horse battery staple")
                        .param("passwordConfirmation", "correct horse battery staple"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "privacyAccepted"));

        verify(registrations, never()).register(any(), any(), any());
    }

    @Test
    void echoesTheAddressAsTypedAndSaysWhatToDoWhenTheEmailDoesNotArrive() throws Exception {
        // The most load-bearing copy in the MVP (frontend brief, page 8). There
        // is no resend form, so if this page does not say "register again",
        // somebody whose email never arrives has no way out at all.
        mockMvc.perform(get(AuthPaths.CHECK_EMAIL)
                        .flashAttr(RegistrationController.REGISTERED_EMAIL, "Ivan@Example.COM"))
                .andExpect(status().isOk())
                // As typed, not as normalised: a reader who typed capitals
                // would not recognise ivan@example.com as what they just sent,
                // and seeing it back is how a typo is caught.
                .andExpect(content().string(containsString("Ivan@Example.COM")))
                .andExpect(content().string(containsString("папката със спам")))
                .andExpect(content().string(containsString("регистрирайте се отново")))
                // Seven, read from TokenPurpose rather than typed into the
                // sentence -- the same seven days as the purge (ADR-002).
                .andExpect(content().string(containsString("7 дни")));
    }

    @Test
    void sendsSomebodyWhoOpensTheNoticePageDirectlyBackToTheForm() throws Exception {
        // Without the flash attribute there is no address to echo, and a page
        // reading "we have sent a confirmation link to ." is worse than no
        // page. A refresh or a bookmark lands here.
        mockMvc.perform(get(AuthPaths.CHECK_EMAIL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(AuthPaths.REGISTER));
    }

    @Test
    void confirmsAnAddressFromTheLinkTheEmailCarries() throws Exception {
        when(registrations.verifyEmail("good-token"))
                .thenReturn(new EmailVerificationOutcome.Verified(1L));

        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL).param(AuthPaths.TOKEN_PARAMETER, "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Адресът е потвърден")));
    }

    @Test
    void treatsASecondClickAsReassuranceRatherThanAnError() throws Exception {
        // Ordinary rather than exceptional: a mail client that prefetches links
        // confirms the address before the user ever clicks it.
        when(registrations.verifyEmail("good-token"))
                .thenReturn(new EmailVerificationOutcome.AlreadyVerified(1L));

        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL).param(AuthPaths.TOKEN_PARAMETER, "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Адресът вече е потвърден")));
    }

    @Test
    void offersTheOnlyRecoveryThereIsOnADeadLink() throws Exception {
        when(registrations.verifyEmail("old-token"))
                .thenReturn(new EmailVerificationOutcome.DeadLink());

        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL).param(AuthPaths.TOKEN_PARAMETER, "old-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Връзката вече не е валидна")))
                .andExpect(content().string(
                        containsString("регистрирайте се отново")));
    }

    @Test
    void showsAForgedTokenTheSamePageAsAnExpiredOne() throws Exception {
        // The service distinguishes them and logs the difference; the page must
        // not, or it tells somebody probing the endpoint whether their forgery
        // was well-formed.
        when(registrations.verifyEmail("forged")).thenReturn(new EmailVerificationOutcome.Invalid());
        when(registrations.verifyEmail("expired")).thenReturn(new EmailVerificationOutcome.DeadLink());

        // The state the template branches on, rather than the bytes of the
        // response: two renders of the same page differ by a fresh CSRF token
        // and by the token echoed into the language-switcher links, neither of
        // which tells anybody anything about the token they sent.
        assertThat(stateOfVerify("forged")).isEqualTo("DEAD_LINK");
        assertThat(stateOfVerify("expired")).isEqualTo("DEAD_LINK");
    }

    @Test
    void answersABareVerificationLinkWithTheDeadLinkPageRatherThanA400() throws Exception {
        // A truncated copy-paste, or a mail client that strips the query
        // string. Boot's error page would be the alternative.
        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("Връзката вече не е валидна")));

        verify(registrations, never()).verifyEmail(any());
    }

    @Test
    void passesTheTokenThroughUnchanged() throws Exception {
        // The token is signed over its own contents, so a controller that
        // trimmed, lowercased or re-encoded it would turn every valid link
        // into a forgery.
        String token = "1.1793000000.aGVsbG8td29ybGQ_-signature";
        when(registrations.verifyEmail(token)).thenReturn(new EmailVerificationOutcome.Verified(1L));

        mockMvc.perform(get(AuthPaths.VERIFY_EMAIL).param(AuthPaths.TOKEN_PARAMETER, token))
                .andExpect(status().isOk());

        verify(registrations).verifyEmail(eq(token));
    }

    private Object stateOfVerify(String token) throws Exception {
        MvcResult result = mockMvc
                .perform(get(AuthPaths.VERIFY_EMAIL).param(AuthPaths.TOKEN_PARAMETER, token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Връзката вече не е валидна")))
                .andReturn();
        return result.getModelAndView().getModel().get("state");
    }
}
