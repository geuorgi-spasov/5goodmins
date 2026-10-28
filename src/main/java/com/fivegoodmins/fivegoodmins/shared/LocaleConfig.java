package com.fivegoodmins.fivegoodmins.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Which language a request is answered in (ADR-008).
 *
 * <p><strong>This exists because the {@code MessageSource} was made strict, and
 * a strict one needs a resolver that cannot ask it for a language it does not
 * have.</strong> {@code MessageSourceConfig} turns off locale fallback and
 * there is no default-locale bundle, so an unresolvable code throws. Boot's
 * default resolver is an {@code AcceptHeaderLocaleResolver} with no default
 * locale, which returns whatever the browser asked for -- so a visitor whose
 * browser sends {@code Accept-Language: de} would have resolved to German, and
 * the first template to render a message would have thrown
 * {@code NoSuchMessageException} on the home page. Nothing in the application
 * reaches that today, because no page exists yet; it would have been found by
 * the first person with a German browser.
 *
 * <p>So the locale is <strong>clamped to the two languages that exist</strong>.
 * There are exactly two ways a locale enters this application and both are
 * now closed: a web request, through the resolver below, and
 * {@code app_user.locale} for emails, through ck_app_user_locale. Anything else
 * becomes Bulgarian rather than an exception.
 *
 * <p>A cookie rather than the {@code Accept-Language} header alone, as ADR-008
 * requires: the switcher has to be able to override a browser whose language is
 * not the one the reader wants, and a nightly job has no header to read anyway
 * (which is why {@code app_user.locale} is stored separately). The header is
 * still honoured for a first visit, so a Bulgarian browser gets Bulgarian and
 * an English one gets English without anybody clicking anything.
 *
 * <p><strong>The switcher is the {@link LocaleChangeInterceptor} below, and
 * wiring it exposed a hole in the clamping above.</strong> The interceptor
 * calls {@code localeResolver.setLocale(...)} with whatever
 * {@code StringUtils.parseLocale} made of the query parameter -- it never goes
 * near {@code parseLocaleValue}, which only ever sees the cookie. So
 * {@code ?lang=de} would have set German directly, written a {@code de} cookie,
 * and thrown {@code NoSuchMessageException} on the first label of the next
 * page: a 500 reachable by editing the address bar. {@code setLocale} is
 * therefore clamped too. The rule is the same one stated above -- anything
 * unsupported becomes Bulgarian rather than an exception -- it just has two
 * doors rather than one.
 */
@Configuration(proxyBeanMethods = false)
class LocaleConfig {

    /** Bulgarian-first (ADR-008): the default, and the fallback for anything unsupported. */
    static final Locale DEFAULT = Locale.forLanguageTag("bg");

    /**
     * The languages with a bundle. Matches messages_bg / messages_en and
     * ck_app_user_locale; a third language is a properties file, this list, and
     * the CHECK constraint, in one commit.
     */
    static final List<Locale> SUPPORTED = List.of(DEFAULT, Locale.forLanguageTag("en"));

    /**
     * The query parameter the switcher uses. Shared with {@link LocaleSwitch},
     * which builds the links, so that renaming it is one edit rather than two
     * that have to agree.
     */
    static final String LANGUAGE_PARAMETER = "lang";

    /** A year: long enough that a reader sets it once. */
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    /**
     * Named {@code localeResolver} because that is the name
     * {@code DispatcherServlet} looks up -- it is not resolved by type. Boot's
     * own is {@code @ConditionalOnMissingBean(name = "localeResolver")}, so
     * this replaces it; a differently named bean of the same type would be
     * ignored in silence and the default would keep running.
     */
    @Bean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new SupportedLocalesOnly();
        resolver.setCookieMaxAge(COOKIE_MAX_AGE);
        resolver.setCookiePath("/");
        // Lax rather than Strict, for the same reason as the session cookie:
        // every emailed link is a cross-site navigation (ADR-005), and under
        // Strict a reader following one would arrive in the wrong language.
        resolver.setCookieSameSite("Lax");
        // Deliberately not Secure: a language preference is not a secret, and
        // the dev profile runs on plain HTTP where a Secure cookie is dropped.
        return resolver;
    }

    /**
     * The switcher's write half: {@code ?lang=en} on any GET, which the
     * interceptor turns into a call to the resolver above and therefore into a
     * cookie. A link rather than a form, because changing the reading language
     * of a page is not a state change worth a POST and a CSRF token.
     *
     * <p>{@code ignoreInvalidLocale} covers the shapes that are not a locale at
     * all -- {@code ?lang=}, {@code ?lang=../..}, {@code ?lang=%00}. Without
     * it {@code StringUtils.parseLocale} throws
     * {@code IllegalArgumentException} out of {@code preHandle}, which is a
     * 500 rather than a page in the wrong language. The clamp on
     * {@code setLocale} covers the other shape: a well-formed language tag for
     * a language this application does not have.
     *
     * <p>Only GET, which is the default. A {@code lang} parameter is not
     * something a POST should carry, and honouring one on a form submission
     * would mean a redisplayed form with validation errors could come back in
     * a different language than it went out in.
     */
    @Bean
    LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANGUAGE_PARAMETER);
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    /**
     * Registers the interceptor. A {@code WebMvcConfigurer} rather than
     * {@code @EnableWebMvc} plus a full configuration: the latter switches off
     * Boot's MVC auto-configuration entirely, which would take the Thymeleaf
     * view resolver, the static-resource handlers and the message converters
     * with it.
     */
    @Bean
    WebMvcConfigurer localeChangeRegistration(LocaleChangeInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    /**
     * A {@link CookieLocaleResolver} that can only ever return a language this
     * application has a bundle for.
     *
     * <p>{@code parseLocaleValue} is the single place the cookie is read, so
     * overriding it covers a forged or stale cookie -- {@code locale=de} from a
     * hand-edited browser store, or {@code locale=fr} left over from a future
     * version that dropped French. Returning null rather than throwing is
     * deliberate: null falls through to the default function below, where an
     * exception would become a 500 on a page whose only problem is a cookie.
     */
    private static final class SupportedLocalesOnly extends CookieLocaleResolver {

        private SupportedLocalesOnly() {
            setDefaultLocaleFunction(SupportedLocalesOnly::bestFromHeader);
        }

        @Override
        protected Locale parseLocaleValue(String localeValue) {
            return supported(Locale.forLanguageTag(localeValue));
        }

        /**
         * The other door: {@link LocaleChangeInterceptor} reaches the resolver
         * here, with a locale parsed from the query string rather than from
         * the cookie, so {@code parseLocaleValue} never sees it.
         *
         * <p>Unsupported becomes the default rather than null, because null
         * here means "forget the preference" -- it clears the cookie -- and
         * {@code ?lang=de} should leave a reader on Bulgarian, not on whatever
         * their browser asks for next.
         */
        @Override
        public void setLocale(HttpServletRequest request,
                              @Nullable HttpServletResponse response,
                              @Nullable Locale locale) {
            super.setLocale(request, response, locale == null ? null : clamp(locale));
        }

        private static Locale clamp(Locale candidate) {
            Locale match = supported(candidate);
            return match == null ? DEFAULT : match;
        }

        /**
         * The best {@code Accept-Language} match among the supported languages,
         * or Bulgarian.
         *
         * <p>{@code getLocales()} is already ordered by the header's q-values
         * -- the servlet container parses them -- so the first entry that
         * matches a supported language is the best match. Matching is on
         * {@link Locale#getLanguage()} rather than on equality, which is what
         * makes {@code en-GB} and {@code en-US} resolve to {@code en} instead
         * of falling through to Bulgarian. Country and variant are discarded
         * because there is one bundle per language and nothing else to choose
         * between.
         */
        private static Locale bestFromHeader(HttpServletRequest request) {
            Enumeration<Locale> requested = request.getLocales();
            while (requested.hasMoreElements()) {
                Locale match = supported(requested.nextElement());
                if (match != null) {
                    return match;
                }
            }
            return DEFAULT;
        }

        private static Locale supported(Locale candidate) {
            return SUPPORTED.stream()
                    .filter(available -> available.getLanguage().equals(candidate.getLanguage()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
