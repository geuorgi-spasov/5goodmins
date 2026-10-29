package com.fivegoodmins.fivegoodmins.action;

/**
 * The public URLs of an action, in one place, because emails have to build them
 * before the pages that serve them exist.
 *
 * <p><strong>Neither path has a controller yet</strong> -- the action list is
 * page 1 of the frontend brief and the detail page is page 2. The welcome email
 * built in week 7 nevertheless has to put a link next to each action, and a
 * link assembled inside a template is a link nobody finds when the route is
 * finally chosen. So the shape is decided here, once:
 *
 * <ul>
 * <li>{@code /actions} for the list, which is <em>also</em> reachable at
 * {@code /} -- the brief calls page 1 "action list / home". A named path as
 * well as the root, so an email can link to the listing without depending on
 * what the home page turns out to be.</li>
 * <li>{@code /actions/{id}} for one action, matching ADR-001's
 * {@code /api/v1/actions} for the JSON twin of the same resource.</li>
 * </ul>
 *
 * <p><strong>Two consequences, named rather than discovered.</strong> Until
 * those controllers exist, a link in a delivered welcome email reaches
 * {@code .anyRequest().authenticated()} and lands on the login page -- the same
 * class of accepted cost as ADR-002's note that {@code redirect:/} is a 404
 * today, and it resolves itself when page 1 lands. And when page 2 is written
 * it must map <em>this</em> constant: a detail page at a different path would
 * leave every link in every already-delivered email pointing at nothing, which
 * is exactly why page 19 of the brief says "404 matters: deleted actions land
 * here from old digest links".
 */
public final class ActionPaths {

    public static final String LIST = "/actions";

    private ActionPaths() {
    }

    public static String detail(long id) {
        return LIST + "/" + id;
    }
}
