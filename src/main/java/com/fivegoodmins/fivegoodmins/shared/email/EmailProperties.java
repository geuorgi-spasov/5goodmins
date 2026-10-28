package com.fivegoodmins.fivegoodmins.shared.email;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Who this application's email comes from, and what host its links point at.
 *
 * <p>Both required, with no defaults, and the application does not start
 * without them -- the same decision as the absent datasource in
 * application.yaml and the absent {@code app.token.secret}, for the same
 * reason. A committed default would be a plausible wrong value that fails
 * quietly: mail sent {@code from} an address this domain is not authorised to
 * send for is mail that lands in spam and damages the sending reputation every
 * later digest depends on (ADR-005), and a {@code base-url} left at
 * {@code localhost} puts a dead link in a real mailbox. Both are discovered by
 * a user, days later. Startup naming the missing property is the cheapest
 * version of the same information.
 *
 * <p>Production supplies {@code APP_MAIL_FROM} and {@code APP_MAIL_BASE_URL}.
 * Local runs get them from application-dev.yaml, tests from
 * src/test/resources/application.properties.
 *
 * @param from     the envelope and header sender, an address on a domain whose
 *                 SPF and DKIM records name the provider (ADR-005: plan a day
 *                 for this). No display name here -- that is {@link #fromName}.
 * @param fromName the human-readable sender, shown instead of the address by
 *                 every mail client. Optional, and the same in both languages:
 *                 it is a brand name, not a user-facing string the bundle owns
 *                 (ADR-008).
 * @param baseUrl  the site's public origin, without a trailing slash, used to
 *                 build the absolute links an email has to carry. A nightly job
 *                 and an after-commit listener have no request to read the host
 *                 from, which is why this is configuration and not something
 *                 derived from {@code ServletUriComponentsBuilder}. It must be
 *                 the canonical public origin rather than the host Tomcat sees:
 *                 behind Cloudflare and Railway those differ, and the one in
 *                 the link has to be the one the user can open.
 */
@ConfigurationProperties("app.mail")
@Validated
public record EmailProperties(
        @NotBlank String from,
        String fromName,
        @NotBlank String baseUrl) {

    public EmailProperties {
        // Trailing slash removed once, here, rather than guarded at every place
        // a link is built. "https://5goodmins.com/" + "/verify-email" is a URL
        // that works in a browser and looks wrong in an email.
        if (baseUrl != null) {
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }
}
