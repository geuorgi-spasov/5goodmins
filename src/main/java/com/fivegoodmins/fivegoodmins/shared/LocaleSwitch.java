package com.fivegoodmins.fivegoodmins.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Locale;

/**
 * The language switcher's read half: the link for each language, pointing at
 * the page the reader is already on (ADR-008).
 *
 * <p>{@link LocaleConfig}'s interceptor does the work; all this decides is
 * where the links go. They have to come from Java rather than from the
 * template, because Thymeleaf 3.1 removed the {@code #request} object -- the
 * expression that would have built these in the layout no longer exists, and
 * the usual replacement is exactly this: put what the view needs in the model.
 *
 * <p><strong>Every link is a path, never an absolute URL, and it is built from
 * the request rather than from anything the reader sent.</strong> A switcher
 * that redirected to {@code Referer} would be the cheapest open redirect on
 * the site -- a header the browser is told to send by whoever linked here.
 * {@code getRequestURI()} and {@code getQueryString()} are the server's own
 * view of what it is answering, so the worst a visitor can do is choose which
 * of their own pages to reload in the other language.
 *
 * <p><strong>Unscoped, after three candidate scopes turned out not to work.</strong>
 * The concern is fair -- this runs on every controller, which one day includes
 * ADR-001's {@code /api/v1/actions}, where two language links are built and
 * nobody reads them. The three ways to narrow it:
 *
 * <ul>
 *   <li>{@code @ControllerAdvice(annotations = Controller.class)} is the
 *       obvious one and it excludes nothing. {@code @RestController} is itself
 *       meta-annotated {@code @Controller}, and {@code HandlerTypePredicate}
 *       matches through {@code AnnotatedElementUtils.hasAnnotation}, which
 *       searches meta-annotations. Measured, not assumed.</li>
 *   <li>{@code basePackages} cannot express the distinction in a
 *       package-by-feature layout (ADR-003). {@code action/} will hold the
 *       HTML list controller for page 1 <em>and</em> the JSON controller for
 *       {@code /api/v1/actions}. They are the same package by design.</li>
 *   <li>A {@code HandlerInterceptor} adding to a non-null {@code ModelAndView}
 *       in {@code postHandle} would exclude JSON handlers correctly, and
 *       trades the problem for a worse one: at {@code postHandle} the
 *       {@code ModelAndView} for a {@code redirect:} view is already built,
 *       and objects added to it can be appended to the redirect URL as query
 *       parameters.</li>
 * </ul>
 *
 * <p>So the cost stays, and it is two string builds on a request that ignores
 * them. What would have been expensive was the exception in
 * {@link #samePageIn}, which this advice would have carried onto every JSON
 * endpoint as well; that is fixed there rather than by hiding from it.
 *
 * <p>The related hazard worth pinning is that a model attribute added to every
 * controller must not end up in a redirect's query string. It does not:
 * {@code RequestMappingHandlerAdapter.ignoreDefaultModelOnRedirect} is true by
 * default from Spring 6, so the default model is dropped on a redirect. That is
 * a default this class depends on rather than sets, which is exactly the kind
 * worth a test -- see {@code LocaleSwitchWebTest}.
 *
 * @see LocaleConfig
 */
@ControllerAdvice
class LocaleSwitch {

    /**
     * One entry per supported language.
     *
     * @param tag     {@code bg} / {@code en}: the value for {@code ?lang=} and
     *                the suffix of the {@code language.*} message key, so the
     *                template never hardcodes either
     * @param url     this page, in that language
     * @param current whether it is the language being read right now, so the
     *                switcher can mark it instead of offering it
     */
    record LanguageLink(String tag, String url, boolean current) { }

    /**
     * Added to the model of every controller in the application. The cost is
     * two string builds per request, which is the price of the switcher being
     * in the layout rather than on the pages that remembered it.
     */
    @ModelAttribute("languageLinks")
    List<LanguageLink> languageLinks(HttpServletRequest request) {
        String active = LocaleContextHolder.getLocale().getLanguage();
        return LocaleConfig.SUPPORTED.stream()
                .map(Locale::getLanguage)
                .map(tag -> new LanguageLink(tag, samePageIn(request, tag), tag.equals(active)))
                .toList();
    }

    /**
     * This page in the given language, or -- if the current query string
     * cannot be rebuilt safely -- this path in the given language.
     *
     * <p><strong>The catch is the whole method, and leaving it out was a 500 on
     * any page, from the address bar.</strong> {@code build(true)} tells Spring
     * the path and query are already encoded, and Spring does not take that on
     * trust: {@code HierarchicalUriComponents} verifies every component and
     * throws {@code IllegalArgumentException} on anything it considers
     * invalid. {@code getQueryString()} is raw, straight off the wire. So the
     * exception comes out of a {@code @ModelAttribute} method on a page with
     * nothing to do with the language switcher -- and the error dispatch to
     * {@code /error} reaches {@code BasicErrorController}, also a controller,
     * also covered by this advice, where building the error page throws again.
     *
     * <p><strong>What actually triggers it is an ordinary {@code =} inside a
     * parameter value</strong>, and finding that took measuring rather than
     * reasoning. The obvious guess -- a malformed percent sequence like
     * {@code ?x=100%} -- never arrives: Tomcat 11 answers 400 itself, and so
     * it does for a raw space, {@code |}, <code>{</code>, {@code ^},
     * {@code \}, {@code "} and {@code <}. What Tomcat passes through happily,
     * because RFC 3986 allows it in a query, is {@code ?t=abc==} or
     * {@code ?next=http://x/?a=b}. Spring is stricter one level down:
     * {@code query(String)} splits on {@code [^&=]+} and then verifies each
     * value as {@code Type.QUERY_PARAM}, which forbids {@code =} and {@code &}
     * because they are its own delimiters. Base64 padding, a nested URL, a
     * JWT -- all of them are a 500.
     *
     * <p>Nothing in the registration flow reaches it today, and that is luck
     * worth naming rather than a reason to leave it: {@code SignedTokenService}
     * encodes its MAC {@code withoutPadding()}, so a verification link carries
     * no {@code =}. ADR-001's {@code /api/v1/actions?q=...} is one query
     * parameter away from it.
     *
     * <p><strong>Why not drop {@code build(true)} and let the builder encode?</strong>
     * Because the input really is encoded, and encoding it twice turns a
     * {@code %20} in the path into {@code %2520} -- the switcher would point at
     * a different URL than the one being read.
     *
     * <p><strong>Why not rebuild the query from {@code getParameterMap()},</strong>
     * which is already decoded and could never throw? Because that map merges
     * the query string with the <em>form body</em> on a POST, and the POST this
     * advice runs on most often is the registration form. The language links on
     * a redisplayed form would carry the submitted password in their href. That
     * is not a tidier version of this method; it is a credential leak.
     *
     * <p>So the query is passed through as it arrived, and what cannot be
     * passed through is degraded rather than thrown, in three tiers: this URL,
     * else this path, else a bare {@code ?lang=} that the browser resolves
     * against whatever it is already showing. The reader always keeps a working
     * language switcher.
     *
     * <p><strong>All three tiers are inside a guard, which took a second
     * review to get right.</strong> The first version guarded only the query
     * and then called {@code build(true)} on the path in the fallback -- same
     * method, same verification, no guard -- so a path like {@code /a%zzb}
     * made the fallback rethrow precisely the exception the guard had just
     * caught. Tomcat rejects those paths today, which is the same
     * container-strictness argument this class refuses to rely on one
     * paragraph above.
     */
    private static String samePageIn(HttpServletRequest request, String tag) {
        String path = request.getRequestURI();
        String query = request.getQueryString();

        // Best: this exact URL, in the other language.
        try {
            UriComponentsBuilder here = UriComponentsBuilder.fromPath(path);
            if (query != null) {
                here.query(query);
            }
            return withLanguage(here, tag);
        } catch (IllegalArgumentException unrebuildable) {
            // Deliberately not logged, here or below. Both are reachable by
            // anyone editing a URL, so logging them is a log line anybody can
            // write at will.
        }

        // The query could not be rebuilt. The path on its own still switches
        // the language of the page being read.
        try {
            return withLanguage(UriComponentsBuilder.fromPath(path), tag);
        } catch (IllegalArgumentException unrebuildablePath) {
            // Neither could the path -- {@code /a%zzb} throws the same way the
            // query does, and the first version of this method called
            // build(true) on the path OUTSIDE the guard, which made the
            // fallback rethrow exactly what the guard had just caught.
            //
            // A query-only relative reference is the one answer that needs no
            // encoding of anything: RFC 3986 section 5.3 resolves "?lang=en"
            // against the current URL, keeping its path and replacing its
            // query. The tag is "bg" or "en" from LocaleConfig.SUPPORTED, not
            // from the request, so there is nothing left here to be invalid.
            return "?" + LocaleConfig.LANGUAGE_PARAMETER + "=" + tag;
        }
    }

    /**
     * {@code replaceQueryParam} rather than {@code queryParam}: a reader who has
     * already switched once is on a URL that carries {@code ?lang=}, and
     * appending a second value would make the parameter a list whose first
     * entry the interceptor reads -- clicking "English" on a {@code ?lang=bg}
     * page would stay Bulgarian.
     */
    private static String withLanguage(UriComponentsBuilder here, String tag) {
        return here.replaceQueryParam(LocaleConfig.LANGUAGE_PARAMETER, tag).build(true).toUriString();
    }
}
