package com.fivegoodmins.fivegoodmins.action;

/**
 * An action as a list reads it: a DTO projection, never the entity.
 *
 * <p>ADR-003's rule is that entities do not leave the service layer and that
 * list views use projections. The reason is visible in this record's shape --
 * it carries four columns, so the query behind it reads four columns, and
 * nothing it is handed to can lazily reach {@code action.author} or
 * {@code action.categories} and find itself outside a transaction with
 * {@code open-in-view=false}. A template holding an entity is a
 * {@code LazyInitializationException} waiting for a designer to add one field.
 *
 * <p><strong>No categories or regions, and that is a choice with a date on
 * it.</strong> The badges the frontend brief asks for live in two element
 * collections, so adding them means either a query per action or a join that
 * returns an action once per tag -- the duplication ADR-005 avoids with
 * {@code EXISTS} in the digest. The welcome email is unpersonalized chrome and
 * does not need them (ADR-005); the digest and page 1 do, and they can pay for
 * a richer projection when they are built.
 *
 * <p><strong>{@code description} is the whole column, not an excerpt.</strong>
 * Truncating in SQL would halve what is read, and would also decide in the
 * query how long a line in an email is. Five rows of up to 5,000 characters,
 * once per account verification, is not worth moving a presentation decision
 * into a projection for.
 *
 * @param id         the detail page's path segment; see {@link ActionPaths}.
 * @param title      shown as the link text.
 * @param actionType labelled from the message bundle under
 *                   {@code actionType.<constant>} (ADR-008, ADR-017), never
 *                   from a string in Java.
 */
public record ActionSummary(Long id, String title, ActionType actionType, String description) {
}
