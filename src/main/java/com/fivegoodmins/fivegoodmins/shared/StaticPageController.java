package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page 5 of the frontend brief: the privacy policy.
 *
 * <p><strong>Here because the registration form links to it, and a GDPR
 * consent whose policy link lands on a login form is not a consent that can be
 * defended.</strong> Before this existed, {@code /privacy} fell through to
 * {@code anyRequest().authenticated()} and redirected an anonymous visitor --
 * the only kind of visitor who sees that checkbox -- to {@code /login}.
 *
 * <p><strong>The text is a stub, and it is a truthful stub rather than
 * placeholder filler.</strong> It states what registration actually stores and
 * what it is used for, which is readable off the schema and ADR-002, and says
 * plainly that the full policy is still being written. That is worth more to a
 * reader than lorem ipsum and it is not a substitute for the real document:
 * the legal text is the site owner's to write, not the backend's to invent.
 *
 * <p>Page 4, About, is the same template per the brief and is not added here --
 * nothing links to it yet, and a route with no caller is a route nobody
 * maintains.
 */
@Controller
class StaticPageController {

    @GetMapping(AuthPaths.PRIVACY)
    String privacy() {
        return "privacy";
    }
}
