package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import com.fivegoodmins.fivegoodmins.shared.token.TokenVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Creating an account, confirming the address it claims, and removing the
 * accounts that never confirmed one (ADR-002).
 *
 * <p>Three rules, and all three exist for the same reason: the email address
 * is the product. It is the digest destination (ADR-005), the login
 * identifier, and the only thing linking a row to a person. An address nobody
 * proved they can read produces bounces and spam complaints, and those damage
 * deliverability for every future email to every other user.
 *
 * <ol>
 *   <li><strong>Verification is mandatory.</strong> A new account cannot log
 *       in and receives no mail until {@code email_verified_at} is set. The
 *       gate is in {@code AppUserDetailsService}, above Spring Security's own
 *       status checks, so that an unverified address is not distinguishable
 *       from an unknown one at the login form.</li>
 *   <li><strong>An unverified address can be taken over.</strong> Registering
 *       over an existing unverified row is allowed, so that typing someone
 *       else's address cannot squat it. See {@link #register}.</li>
 *   <li><strong>Unverified rows are purged after seven days</strong>, on the
 *       same window as the verification token's lifetime.</li>
 * </ol>
 *
 * <p><strong>This is not a DDoS defence</strong> and must not be argued as
 * one: a flood of traffic never completes a registration. It raises the cost
 * of fake accounts, and that is all. Volume is Cloudflare's job, and
 * per-IP/per-address limits on this endpoint are Bucket4j's (ADR-002) --
 * notably because {@link #register} will happily email the owner of an
 * unverified address once per request until it is capped.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /**
     * How long an unverified row survives, and the same {@link java.time.Duration}
     * the verification token is signed with -- read from the enum rather than
     * written again here.
     *
     * <p>ADR-002 requires these to be one number. A token outliving its row
     * resolves to an account that no longer exists; a token dying well before
     * the purge leaves a user holding a dead link while the row it names sits
     * there waiting. Either is a support request about a link that "does not
     * work", which is the hardest class of bug to hear about accurately.
     */
    private static final TokenPurpose VERIFICATION = TokenPurpose.EMAIL_VERIFICATION;

    /**
     * BCrypt hashes the first 72 <em>bytes</em> and Spring Security's encoder
     * rejects anything longer rather than silently truncating it.
     *
     * <p>Bytes, not characters, and that distinction is not academic for this
     * application: Cyrillic is two bytes per character in UTF-8, so a
     * thirty-seven-character Bulgarian passphrase is already over the limit
     * while an ASCII one of the same length is half of it. A form advertising
     * "maximum 72 characters" would therefore be wrong for exactly the users
     * this site is for.
     */
    private static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Eight characters, counted as characters because that is what the person
     * typing counts.
     *
     * <p>A floor, not a policy: no composition rules, no forced rotation,
     * nothing that pushes people towards "Password1!". Rate limiting rather
     * than lockout is what defends the login form (ADR-002); length is what
     * defends the hash.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** varchar(80) in the migration. Stated so the failure is a message, not a 500. */
    private static final int MAX_DISPLAY_NAME_LENGTH = 80;

    /** varchar(254): the practical RFC 5321 maximum for an address. */
    private static final int MAX_EMAIL_LENGTH = 254;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SignedTokenService tokens;
    private final Clock clock;

    RegistrationService(AppUserRepository users,
                        PasswordEncoder passwordEncoder,
                        SignedTokenService tokens,
                        Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Registers an address, or declines to say that it could not.
     *
     * <p><strong>Re-registration over an unverified row replaces it, rather
     * than updating it in place.</strong> That is the one decision in this
     * method worth defending, and it is a security decision rather than a
     * stylistic one.
     *
     * <p>Updating the row in place would leave the previous occupant's
     * verification token valid: same user id, same key, same purpose, so it
     * still verifies. The damaging direction is the one that looks harmless.
     * Suppose the real owner registers first, does not click the link, and
     * someone else then re-registers over the row with a password of their
     * own. If the row survives, the owner's original link -- sitting in their
     * own mailbox, from a registration they really made -- verifies the
     * stranger's account. They would then be looking at a confirmed account on
     * their own address whose password they do not know.
     *
     * <p>Deleting and inserting makes the superseded token resolve to a row
     * that no longer exists, so it fails as {@link
     * EmailVerificationOutcome.NoSuchAccount}. That is revocation without a
     * revocation list, which is the only way to get it when the token carries
     * its own claims and there is no token table.
     *
     * <p>The explicit {@code flush()} between the two is load-bearing.
     * Hibernate orders inserts before deletes within a flush, so without it
     * both statements reach the database in the wrong order and the INSERT
     * fails on uq_app_user_email -- for a case that is supposed to work.
     *
     * <p><strong>A verified address returns {@link
     * RegistrationOutcome.AddressTaken} and writes nothing.</strong> The caller
     * renders the identical "check your inbox" page for both outcomes; see
     * {@link RegistrationOutcome}.
     *
     * <p><strong>The concurrent case is left to the database.</strong> Two
     * simultaneous registrations for the same free address can both pass the
     * lookup above and both insert; uq_app_user_email rejects the loser with a
     * {@code DataIntegrityViolationException}, which propagates. No duplicate
     * row is ever created -- the constraint is what guarantees that, not the
     * check -- and the cost is that one of two users racing for the same
     * address in the same millisecond sees an error page and succeeds on retry.
     * Catching it here is not possible as written: a constraint violation
     * marks the transaction rollback-only, so returning a tidy outcome from
     * inside it would fail again at commit. The fix, if it ever matters, is to
     * catch it outside the transaction boundary.
     *
     * @param email       as typed; normalized here through
     *                    {@link AppUser#normalizeEmail}
     * @param rawPassword as typed, never logged, never stored
     * @param displayName as typed; trimmed here
     * @throws IllegalArgumentException if any argument is outside what the
     *                                  schema or the encoder accepts. A
     *                                  backstop, not the user-facing check:
     *                                  the form validates first and renders
     *                                  messages from the bundle. Reaching one
     *                                  of these is a bug in the caller.
     */
    @Transactional
    public RegistrationOutcome register(String email, String rawPassword, String displayName) {
        String address = requireValidEmail(email);
        String name = requireValidDisplayName(displayName);
        requireUsablePassword(rawPassword);

        Optional<AppUser> existing = users.findByEmail(address);
        if (existing.isPresent()) {
            AppUser occupant = existing.get();
            if (occupant.isEmailVerified()) {
                // Nothing written, nothing emailed, and the caller is about to
                // render the same page it renders below.
                return new RegistrationOutcome.AddressTaken();
            }
            log.info("registration replaces unverified account {}", occupant.getId());
            users.delete(occupant);
            users.flush();
        }

        Instant now = clock.instant();
        AppUser registered = users.saveAndFlush(
                new AppUser(address, passwordEncoder.encode(rawPassword), name, now));
        // saveAndFlush, not save: the id is needed for the token on the next
        // line, and an id read before the INSERT has reached the database is
        // an id that may yet be rolled back into a link somebody received.

        return new RegistrationOutcome.Registered(
                registered.getId(), tokens.issue(registered.getId(), VERIFICATION));
    }

    /**
     * Confirms the address behind a verification link.
     *
     * <p>The purpose is passed to {@code verify} rather than read out of the
     * token, so an unsubscribe token offered here simply fails to verify.
     *
     * <p>Idempotent on purpose, and distinguishably so: a second click returns
     * {@link EmailVerificationOutcome.AlreadyVerified} rather than {@link
     * EmailVerificationOutcome.Verified}, which is what will keep the welcome
     * email (ADR-005) from going out twice when a mail client prefetches the
     * link and the user then clicks it.
     *
     * <p>A valid signature does not imply a row. The account may have been
     * purged, superseded by a re-registration, or deleted (ADR-015) since the
     * link was sent, so the lookup can legitimately come back empty.
     */
    @Transactional
    public EmailVerificationOutcome verifyEmail(String token) {
        return switch (tokens.verify(token, VERIFICATION)) {
            case TokenVerification.Invalid ignored -> {
                // The only case worth logging: expired links are ordinary, a
                // bad signature is either a mangled copy-paste or a probe. The
                // token itself is not logged -- a valid one in a log file is a
                // verification somebody else can perform.
                log.warn("rejected an email verification token that failed its signature");
                yield new EmailVerificationOutcome.Invalid();
            }
            case TokenVerification.Expired ignored -> new EmailVerificationOutcome.Expired();
            case TokenVerification.Valid(long userId) -> verifyAccount(userId);
        };
    }

    private EmailVerificationOutcome verifyAccount(long userId) {
        Optional<AppUser> found = users.findById(userId);
        if (found.isEmpty()) {
            log.info("email verification names account {}, which no longer exists", userId);
            return new EmailVerificationOutcome.NoSuchAccount();
        }

        AppUser user = found.get();
        if (user.isEmailVerified()) {
            return new EmailVerificationOutcome.AlreadyVerified(userId);
        }

        // markEmailVerified writes email_verified_at once and ignores later
        // calls; the branch above is what tells the caller which happened, not
        // a second guard. Dirty checking flushes this at commit -- no save()
        // call, because the entity is managed inside this transaction.
        user.markEmailVerified(clock.instant());
        return new EmailVerificationOutcome.Verified(userId);
    }

    /**
     * Deletes accounts that have sat unverified for longer than a verification
     * token lives, and returns how many went.
     *
     * <p>Strictly a cleanup: nothing depends on it running, nothing breaks if
     * it misses a day, and running it twice deletes nothing the first pass
     * left. That is what makes the single-instance assumption behind
     * {@code @Scheduled} (ADR-012) acceptable here -- two instances racing on
     * this would each delete rows the other had already deleted, which is a
     * no-op, unlike two instances sending two digests.
     *
     * <p>What it costs the user: an address abandoned mid-registration becomes
     * registrable again, by anyone. That is the intended effect rather than a
     * side effect -- it is the same rule as the takeover in {@link #register},
     * applied by the clock instead of by a second person.
     */
    @Transactional
    public int purgeUnverifiedAccounts() {
        Instant cutoff = clock.instant().minus(VERIFICATION.ttl());
        int purged = users.deleteUnverifiedCreatedBefore(cutoff);
        if (purged > 0) {
            log.info("purged {} unverified account(s) created before {}", purged, cutoff);
        }
        return purged;
    }

    private static String requireValidEmail(String email) {
        String address = AppUser.normalizeEmail(email);
        if (address.isEmpty()) {
            throw new IllegalArgumentException("email is required");
        }
        if (address.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("email exceeds " + MAX_EMAIL_LENGTH + " characters");
        }
        // No syntax check. Whether an address is deliverable is answered by
        // sending to it and seeing whether the link comes back, which is the
        // whole mechanism this class exists for; a regular expression here
        // would only add a second, wronger opinion. The form's @Email
        // annotation catches the typo that is worth catching early.
        return address;
    }

    private static String requireValidDisplayName(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            // Trimmed first, because ck_app_user_display_name_length compares
            // btrim(display_name) and a name of three spaces is not a name.
            throw new IllegalArgumentException("display name is required");
        }
        if (name.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "display name exceeds " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return name;
    }

    private static void requireUsablePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password is shorter than " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            // Checked here rather than left to the encoder so the limit is
            // stated in bytes, in one place, next to the reason -- and so the
            // failure is this message instead of BCrypt's.
            throw new IllegalArgumentException(
                    "password exceeds " + MAX_PASSWORD_BYTES + " bytes once encoded as UTF-8");
        }
    }
}
