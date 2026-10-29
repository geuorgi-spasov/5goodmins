package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;

/**
 * Pages 6 and 8 of the frontend brief: the registration form, the "check your
 * inbox" page it leads to, and the page the emailed link lands on.
 *
 * <p><strong>Both registration outcomes render the same page, and that is the
 * whole point of this class.</strong> {@code RegistrationService} already
 * refuses to say whether an address is taken -- it pays BCrypt's ~100ms on
 * both paths so that a stopwatch cannot answer what the page will not. A
 * controller that redirected one outcome and re-rendered the other, or set a
 * different flash message, would hand that answer back in the response. The
 * two branches below are deliberately one branch.
 *
 * <p><strong>There is no resend endpoint and there will not be one</strong>
 * (ADR-002, Rev 14). Registering again over an unverified row replaces it and
 * issues a fresh token, so it already <em>is</em> a resend. That is only true
 * for a user who knows it, which is why
 * {@code verification.notice.resend} is on the page rather than behind a
 * "having trouble?" disclosure: the person who needs it is the person for whom
 * the page has already appeared to fail.
 */
@Controller
class RegistrationController {

    /**
     * Carries the address from the POST to the page that echoes it back.
     * Flash-scoped, so it survives exactly one redirect and is gone on a
     * refresh.
     */
    static final String REGISTERED_EMAIL = "registeredEmail";

    /** Mirrors {@code RegistrationService}'s own ceiling; see its comment for why bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final RegistrationService registrations;

    RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    /**
     * {@code permitAll()} permits everyone, not only the anonymous, so a
     * logged-in visitor can reach this form -- and registering a second
     * account while holding a session for the first is a state nothing
     * downstream handles. Both the GET and the POST turn them away.
     *
     * <p>A trust resolver rather than a null check: anonymous access is on, so
     * there is always an {@code Authentication}, and the one on an anonymous
     * request is an {@code AnonymousAuthenticationToken}. Comparing against
     * null would make every visitor "logged in" and nobody able to register.
     *
     * <p><strong>{@code redirect:/} is a 404 today</strong>, because the action
     * list is page 1 of the frontend brief and is not built. That is a known
     * cost rather than an oversight: the alternatives are worse. Rendering the
     * form anyway is the state this exists to prevent; redirecting to
     * {@code /login} tells someone who is already logged in to log in. A 404
     * for a signed-in visitor who typed {@code /register} is the least wrong
     * answer available, and it becomes the right one the day page 1 lands,
     * with no change here.
     */
    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private static boolean alreadySignedIn(Authentication authentication) {
        return authentication != null && !TRUST.isAnonymous(authentication);
    }

    @GetMapping(AuthPaths.REGISTER)
    String showForm(@ModelAttribute("form") RegistrationForm form, Authentication authentication) {
        if (alreadySignedIn(authentication)) {
            return "redirect:/";
        }
        return "auth/register";
    }

    /**
     * Post/Redirect/Get rather than rendering the notice from the POST
     * directly. A rendered POST response leaves the browser holding a form
     * submission to replay, so a refresh re-registers -- which on an
     * unverified row means deleting and re-inserting it and sending a second
     * email, every time somebody hits F5.
     */
    @PostMapping(AuthPaths.REGISTER)
    String register(@Valid @ModelAttribute("form") RegistrationForm form,
                    BindingResult errors,
                    Authentication authentication,
                    RedirectAttributes flash) {
        if (alreadySignedIn(authentication)) {
            // Before validation, so that a stray POST from a logged-in session
            // never reaches the service at all.
            return "redirect:/";
        }
        checkPasswordByteLength(form, errors);
        checkConfirmationMatches(form, errors);

        if (errors.hasErrors()) {
            // Never redisplay what was typed into a password field. The value
            // would go into the HTML of a page that is, by definition, being
            // returned after something went wrong -- into the browser's
            // back-forward cache, and into any proxy that ignores the
            // no-store header Spring Security sets.
            form.setPassword(null);
            form.setPasswordConfirmation(null);
            return "auth/register";
        }

        // The return value is deliberately ignored. Registered and
        // AddressTaken both mean "we have said what we are going to say, in an
        // email, to the owner of that mailbox" -- see the class comment.
        registrations.register(form.getEmail(), form.getPassword(), form.getDisplayName());

        // As typed, not as normalized: the address is echoed back so that a
        // typo is visible, and a reader who typed Ivan@Example.COM will not
        // recognise ivan@example.com as what they just sent.
        flash.addFlashAttribute(REGISTERED_EMAIL, form.getEmail());
        return "redirect:" + AuthPaths.CHECK_EMAIL;
    }

    /**
     * The "check your inbox" state of page 8.
     *
     * <p>Reached only through the redirect above: without the flash attribute
     * there is no address to echo, and a page saying "we have sent a link to
     * " is worse than no page. A bookmark or a refresh therefore lands back on
     * the form.
     */
    @GetMapping(AuthPaths.CHECK_EMAIL)
    String checkEmail(Model model) {
        // Read off the model rather than bound as a @ModelAttribute parameter:
        // the flash attribute is already in the model by the time a handler
        // runs, and asking for a String parameter would have Spring construct
        // an empty one when it is absent -- which is the case this has to
        // detect, arriving as something indistinguishable from an empty form.
        Object email = model.getAttribute(REGISTERED_EMAIL);
        if (email == null) {
            return "redirect:" + AuthPaths.REGISTER;
        }
        // Read from the enum rather than written into the bundle, so that
        // changing the token's lifetime changes the sentence (ADR-002: the
        // seven days is one number, not several that agree).
        model.addAttribute("expiryDays", TokenPurpose.EMAIL_VERIFICATION.ttl().toDays());
        return "auth/verification-notice";
    }

    /**
     * The result states of page 8.
     *
     * <p>The token is {@code required = false} so that a bare
     * {@code GET /verify-email} -- a truncated copy-paste, a mail client
     * stripping the query string -- is the dead-link page rather than a 400
     * with Boot's error template on it.
     *
     * <p><strong>A token that fails its signature is shown the same dead-link
     * page as one that has expired</strong>, even though the service
     * distinguishes them. The distinction is for the log, where a bad
     * signature is worth seeing; on the page it would only tell someone
     * probing the endpoint whether their forgery was well-formed.
     */
    @GetMapping(AuthPaths.VERIFY_EMAIL)
    String verify(@RequestParam(name = AuthPaths.TOKEN_PARAMETER, required = false) String token, Model model) {
        model.addAttribute("state", stateOf(token).name());
        return "auth/verification-result";
    }

    private VerificationState stateOf(String token) {
        if (token == null || token.isBlank()) {
            return VerificationState.DEAD_LINK;
        }
        return switch (registrations.verifyEmail(token)) {
            case EmailVerificationOutcome.Verified ignored -> VerificationState.CONFIRMED;
            case EmailVerificationOutcome.AlreadyVerified ignored -> VerificationState.ALREADY_CONFIRMED;
            case EmailVerificationOutcome.DeadLink ignored -> VerificationState.DEAD_LINK;
            case EmailVerificationOutcome.Invalid ignored -> VerificationState.DEAD_LINK;
        };
    }

    /**
     * {@code @Size(max = 72)} counts characters and BCrypt truncates at 72
     * <em>bytes</em>, so a 40-character Bulgarian passphrase is 80 bytes and
     * passes a character check. Left to the service, that is an
     * {@code IllegalArgumentException} and a 500 on the registration page --
     * for Bulgarian users only, which is to say for almost everybody.
     */
    private static void checkPasswordByteLength(RegistrationForm form, BindingResult errors) {
        String password = form.getPassword();
        if (password != null
                && password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            errors.rejectValue("password", "validation.password.tooManyBytes");
        }
    }

    /**
     * Rejected onto the confirmation field rather than the password field or
     * the form as a whole: the password is the one the reader meant, and the
     * confirmation is the one they are being asked to look at again.
     */
    private static void checkConfirmationMatches(RegistrationForm form, BindingResult errors) {
        if (form.getPassword() != null
                && !form.getPassword().equals(form.getPasswordConfirmation())) {
            errors.rejectValue("passwordConfirmation", "validation.password.mismatch");
        }
    }

    /** The three states page 8 has to render, which is not the four the service returns. */
    private enum VerificationState {
        CONFIRMED, ALREADY_CONFIRMED, DEAD_LINK
    }
}
