package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-002's rate limiting, on {@code POST /login} and {@code POST /register}
 * and nothing else.
 *
 * <p><strong>The key is {@code request.getRemoteAddr()}, never a header.</strong>
 * The reasoning is ADR-002's and is not repeated here, but the consequence for
 * this class is: it parses no headers at all. Tomcat's {@code RemoteIpValve}
 * has already walked {@code X-Forwarded-For} by the time any filter runs, and
 * {@code server.tomcat.remoteip.trusted-proxies} in application.yaml is what
 * makes it walk past Cloudflare's edge to the real client.
 *
 * <p><strong>Placed before {@code CsrfFilter}, not after it.</strong> This is a
 * choice with a cost on both sides. Before, a spray with no CSRF token is
 * refused here and never reaches a session lookup, which is what a limiter is
 * for -- and an attacker is one GET away from a valid token anyway, so ordering
 * it after buys no protection. The cost is that a client with an expired
 * session, resubmitting a form it cannot succeed with, spends its own
 * allowance on 403s. That is the less surprising of the two, because those
 * really are failed attempts from that address.
 *
 * <p><strong>A rejection is {@code 429} through the error dispatch</strong>
 * rather than a response written here, so the day page 19 of the frontend
 * brief lands this gets a real page in the site layout with the rest of them.
 * Until then it is the whitelabel error page, which is the same state every
 * other error is in -- and {@code Retry-After} is set first, so the page has a
 * number to say when there is a page to say it on. Writing a body here instead
 * would mean a user-facing string in Java, which ADR-008 forbids, or a blank
 * page, which tells a human nothing.
 *
 * <p><strong>The rejection log line is the measurement ADR-002 asked for.</strong>
 * It reports the resolved bucket key next to the raw remote address, which is
 * the only thing in the application that reports what the valve resolved -- so
 * twenty-one POSTs at the deployed site answer whether Railway's proxy appends
 * to {@code X-Forwarded-For} or passes it through, without an echo endpoint
 * that would have to be built and then removed. The cost is one WARN per
 * refused request, which under a sustained attack is a log line per request;
 * the fix, if that ever matters, is one line per key per window behind a
 * second cache, and it is not worth the state today.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * The field name on the registration form. The same string the form posts;
     * {@code RegistrationForm.email} is bound from it.
     */
    private static final String EMAIL_PARAMETER = "email";

    /**
     * Spring Security's own matchers rather than a string comparison against
     * {@code getRequestURI()}, so that this filter and the authentication
     * filter it sits in front of cannot disagree about what "POST /login"
     * means -- a trailing slash or a path parameter that one accepted and the
     * other did not would be a bypass, silently.
     */
    private static final RequestMatcher LOGIN =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthPaths.LOGIN);

    private static final RequestMatcher REGISTER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthPaths.REGISTER);

    private final RateLimiter limiter;
    private final boolean enabled;

    /**
     * Constructed by {@code SecurityConfig} rather than declared as a bean, and
     * that is the point: a {@code Filter} bean is auto-registered with the
     * servlet container by Spring Boot as well as added to the security chain,
     * so it would run twice -- spending two tokens per request -- unless a
     * second {@code FilterRegistrationBean} were added purely to switch the
     * first registration off. No bean, no dance, and the filter's position is
     * declared in the one file that declares every other filter's position.
     */
    public RateLimitFilter(RateLimiter limiter, boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !(LOGIN.matches(request) || REGISTER.matches(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        for (Attempt attempt : attemptsFor(request)) {
            RateLimiter.Verdict verdict = limiter.tryConsume(attempt.scope(), attempt.key());
            if (!verdict.allowed()) {
                refuse(request, response, attempt, verdict);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * {@code /register} spends from two buckets, and spends the per-address one
     * first on purpose: a request refused for flooding one mailbox has still
     * been made by that client, and should count against them.
     */
    private static List<Attempt> attemptsFor(HttpServletRequest request) {
        String client = ClientAddress.bucketKey(request.getRemoteAddr());
        if (LOGIN.matches(request)) {
            return List.of(new Attempt(RateLimitScope.LOGIN_ATTEMPTS, client));
        }

        List<Attempt> attempts = new ArrayList<>(2);
        attempts.add(new Attempt(RateLimitScope.REGISTRATIONS_PER_ADDRESS, client));
        // AppUser.normalizeEmail, not a lowercase of our own: the service keys
        // the account on the normalized form, so any other normalization here
        // would let Alice@example.com and alice@example.com mail one inbox
        // twice per window.
        //
        // Reading a parameter in a filter parses the request body, and Tomcat
        // caches the result, so the controller still binds the form normally.
        // That holds because this form is urlencoded; it would not for a
        // multipart one, and this is the comment that says why that matters.
        String email = AppUser.normalizeEmail(request.getParameter(EMAIL_PARAMETER));
        if (!email.isEmpty()) {
            attempts.add(new Attempt(RateLimitScope.REGISTRATIONS_PER_EMAIL, email));
        }
        return attempts;
    }

    private static void refuse(HttpServletRequest request,
                               HttpServletResponse response,
                               Attempt attempt,
                               RateLimiter.Verdict verdict) throws IOException {
        long seconds = verdict.retryAfterSeconds();
        log.warn("rate limit refused {}: bucket key [{}], remote address [{}], retry after {}s",
                attempt.scope(), attempt.loggableKey(), request.getRemoteAddr(), seconds);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    private record Attempt(RateLimitScope scope, String key) {

        /**
         * The key, except for the bucket whose key is somebody's email address.
         * Registration already refuses to say whether an address has an account
         * (ADR-002), and a log file that answers the question the login page
         * will not is the same leak written down. The remote address is logged
         * next to this and is what an operator would act on anyway.
         */
        String loggableKey() {
            return scope == RateLimitScope.REGISTRATIONS_PER_EMAIL ? "(email not logged)" : key;
        }
    }
}
