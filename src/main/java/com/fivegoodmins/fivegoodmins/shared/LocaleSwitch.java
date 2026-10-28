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

    private static String samePageIn(HttpServletRequest request, String tag) {
        UriComponentsBuilder here = UriComponentsBuilder.fromPath(request.getRequestURI());
        String query = request.getQueryString();
        if (query != null) {
            here.query(query);
        }
        // replaceQueryParam rather than queryParam: a reader who has already
        // switched once is on a URL that carries ?lang=, and appending a second
        // value would make the parameter a list whose first entry the
        // interceptor reads -- clicking "English" on an ?lang=bg page would
        // stay Bulgarian.
        //
        // build(true) says the path and query came in already encoded, which
        // they did: both are read straight off the request. Letting the builder
        // encode them a second time would turn a %20 in the path into %2520.
        return here.replaceQueryParam(LocaleConfig.LANGUAGE_PARAMETER, tag).build(true).toUriString();
    }
}
