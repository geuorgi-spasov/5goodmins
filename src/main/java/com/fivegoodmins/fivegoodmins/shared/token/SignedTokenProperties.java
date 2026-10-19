package com.fivegoodmins.fivegoodmins.shared.token;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The signing key for every link this application emails.
 *
 * <p>Required, with no default, and the application does not start without it.
 * The same reasoning as the absent datasource in application.yaml: a committed
 * default is worse than a missing value because it fails quietly and names the
 * wrong problem. A hardcoded fallback key would be a published key -- it is in
 * the repository -- so every email verification and unsubscribe link in
 * production would be forgeable by anyone who could read this file, and nothing
 * would look wrong.
 *
 * <p>Generating a random key at startup instead is the other tempting answer,
 * and it is also wrong: every restart would invalidate every link already in a
 * mailbox, and two instances would each reject the other's tokens. Both fail as
 * a user reporting that the link does not work, which is the hardest class of
 * bug to find. Startup refusing to proceed is the cheapest possible version of
 * the same information.
 *
 * <p>{@code APP_TOKEN_SECRET} in the environment, in production.
 * {@code openssl rand -base64 48} generates one. Rotating it is a deliberate
 * act with a known consequence -- outstanding verification and unsubscribe
 * links stop verifying -- so it is not something to do casually while the
 * digest is running.
 *
 * @param secret opaque key material, read as UTF-8 bytes. Thirty-two
 *               characters is the floor rather than a recommendation: HMAC
 *               accepts a key of any length and a short one is a short one,
 *               so the length check is the only thing standing between a
 *               passphrase someone typed and a signature worth having. It
 *               cannot measure entropy, which is why the comment above names a
 *               generator.
 */
@ConfigurationProperties("app.token")
@Validated
public record SignedTokenProperties(
        @NotBlank @Size(min = 32) String secret) {
}
