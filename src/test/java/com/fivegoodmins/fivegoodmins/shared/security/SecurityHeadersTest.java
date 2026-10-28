package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment half of the security configuration, over a real Tomcat on a
 * real socket, because none of it is visible to MockMvc: {@code
 * forward-headers-strategy} is a Tomcat valve, and {@code Set-Cookie} on the
 * session is written by the servlet container.
 *
 * <p>Two findings measured on the live site are what these assert. Behind
 * Cloudflare the request reaching the application is plain HTTP, so
 * {@code Strict-Transport-Security} was absent -- Spring Security writes it only
 * when {@code request.isSecure()} -- and JSESSIONID went out with neither
 * {@code Secure} nor {@code SameSite}.
 *
 * <p>No profile is active here, so these are the deployed values; a local run
 * activates {@code dev}, which turns {@code secure} back off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersTest {

    @LocalServerPort
    int port;

    /**
     * NO_PROXY and NEVER: a redirect must be readable rather than followed, and
     * the JVM's default proxy settings have no business in a request to
     * localhost.
     */
    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    @Test
    void sendsAnAnonymousVisitorToTheLoginFormInsteadOfServingAProtectedPath() throws Exception {
        // There are no controllers yet, so this is the authorization rule
        // answering on its own: anyRequest().authenticated() means an
        // unannounced endpoint redirects rather than responds.
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(header(response, "Location")).hasValueSatisfying(
                location -> assertThat(location).endsWith("/login"));
    }

    @Test
    void servesTheLoginPageItRedirectsTo() throws Exception {
        // anyRequest().authenticated() covers /login too, so the reason the
        // redirect above is not a loop is that the path is permitted
        // explicitly. It used to be that DefaultLoginPageGeneratingFilter sat
        // ahead of the authorization filter and answered on its own; that
        // filter stopped answering the moment loginPage() named
        // LoginController's template, which is exactly the day this assertion
        // was written for. What it checks now is that something still serves
        // the path the redirect points at -- see LoginWebTest for the page
        // itself.
        HttpResponse<String> response = get("/login");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<form", "name=\"username\"", "name=\"password\"");
    }

    @Test
    void writesStrictTransportSecurityWhenTheProxySaysTheRequestArrivedOverHttps() throws Exception {
        // What Cloudflare sends. forward-headers-strategy=native is what makes
        // Tomcat believe it, and request.isSecure() is what Spring Security
        // tests before writing the header.
        HttpResponse<String> response = get("/", "X-Forwarded-Proto", "https");

        // Asserted whole rather than by substring, because the policy is the
        // value and not just its presence. Spring Security's default is a year
        // with includeSubDomains and without preload, and all three are
        // deliberate (ADR-002):
        //
        //   * includeSubDomains stays. Without it, an active attacker can
        //     serve http://anything.5goodmins.com and set a cookie scoped to
        //     .5goodmins.com, which the apex then receives -- the session
        //     cookie being Secure stops it leaking, not an attacker planting
        //     one. HSTS on the apex alone does not close that; this does.
        //   * preload is not added. It is the one-way door: submission bakes
        //     the domain into browser binaries, removal takes months of
        //     release trains, and from then on every subdomain of
        //     5goodmins.com must be HTTPS or be unreachable. A year of
        //     max-age already buys the protection that matters.
        //   * A year, because a shorter max-age is a shorter window in which
        //     the protection actually holds, and the only cost is the cost of
        //     the commitment above.
        //
        // If this assertion ever fails on a Spring Security upgrade, that is
        // the decision changing underneath us, which is exactly the thing
        // worth being told about.
        assertThat(header(response, "Strict-Transport-Security"))
                .hasValue("max-age=31536000 ; includeSubDomains");
    }

    @Test
    void omitsStrictTransportSecurityOnAnHonestlyPlainHttpRequest() throws Exception {
        // Not a gap. The header tells a browser to use HTTPS for a year, and
        // asserting that over a connection that is not HTTPS is how a
        // misconfigured origin becomes unreachable. This is the condition the
        // forwarded header satisfies rather than overrides.
        assertThat(header(get("/"), "Strict-Transport-Security")).isEmpty();
    }

    @Test
    void marksTheSessionCookieSecureSameSiteLaxAndHttpOnly() throws Exception {
        // The redirect above saves the request in the session so the user lands
        // where they were going after logging in, which is what creates the
        // session -- and therefore the cookie -- on an anonymous request.
        assertThat(header(get("/"), "Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
                .startsWith("JSESSIONID=")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("HttpOnly"));
    }

    @Test
    void sendsTheDefaultHardeningHeadersOnEveryResponse() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(header(response, "X-Content-Type-Options")).hasValue("nosniff");
        assertThat(header(response, "X-Frame-Options")).hasValue("DENY");
    }
}
