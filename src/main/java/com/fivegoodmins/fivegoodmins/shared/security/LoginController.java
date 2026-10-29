package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Page 7 of the frontend brief: the login form.
 *
 * <p>Here rather than in {@code user/} because the path is
 * {@link SecurityConfig}'s to define -- {@code formLogin().loginPage(...)}
 * names it, the filter chain permits it, and the day one of those changes this
 * is the file next to it. The user feature owns registration, which is its own
 * flow; it does not own the login page that Spring Security generates a
 * replacement for the moment this class stops existing.
 *
 * <p><strong>Both query parameters exist to be shown, and neither says
 * anything about the account.</strong> {@code ?error} renders one message for
 * every failure -- wrong password, no such address, an address that was never
 * confirmed, a disabled account. That vagueness is the point and it is
 * load-bearing in two places that are easy to undo separately: the login form
 * must not become the account-enumeration oracle that
 * {@code AppUserDetailsService} keeps the registration form from being, and
 * "your address is not confirmed" here would tell anybody with a list of
 * addresses which of them have unconfirmed accounts.
 *
 * @see AppUserDetailsService for why an unverified account is indistinguishable
 *      from an unknown one before the password is ever compared
 */
@Controller
class LoginController {

    @GetMapping(AuthPaths.LOGIN)
    String showForm(@RequestParam(required = false) String error,
                    @RequestParam(required = false) String logout,
                    Model model) {
        // Presence, not value: ?error=whatever is the same page, so a crafted
        // link cannot put text of its own on the login form.
        model.addAttribute("failed", error != null);
        model.addAttribute("loggedOut", logout != null);
        return "auth/login";
    }
}
