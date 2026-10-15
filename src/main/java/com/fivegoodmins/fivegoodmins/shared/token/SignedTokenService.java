package com.fivegoodmins.fivegoodmins.shared.token;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Signed, expiring, stateless tokens for the links this application emails:
 * email verification (ADR-002) and one-click unsubscribe (ADR-005), with
 * password reset to follow. One piece of infrastructure, three uses.
 *
 * <p><strong>No token table.</strong> The token carries its own claims and a
 * MAC over them, so verification is a key and a comparison rather than a
 * lookup. That removes a table, its migration, its index, and the job that
 * would have to delete expired rows from it. What it costs is the thing a table
 * buys: a token cannot be revoked before its expiry, because there is nowhere
 * to record that it was used. For these two purposes that is acceptable, and it
 * is acceptable for a specific reason rather than by assumption -- both are
 * idempotent. Verifying an address twice is
 * {@link com.fivegoodmins.fivegoodmins.user.AppUser#markEmailVerified}, which
 * writes once; unsubscribing twice leaves the user unsubscribed. Replay changes
 * nothing either time.
 *
 * <p><strong>Password reset is the use that will not be idempotent</strong>, and
 * it needs one more thing before it can share this class. A reset link that
 * stays valid for its full lifetime after the password has been changed lets
 * whoever else read that mailbox change the password back. The fix is to sign
 * something that changes when the password does -- the current
 * {@code password_hash}, or a digest of it -- so that using the token
 * invalidates it as a side effect, with no table and no "used" flag. That is an
 * added field in {@link #payload}, not a different mechanism, but it is not
 * here: there is no password reset yet, and a parameter with one possible value
 * is harder to justify than the line that adds it later.
 *
 * <p><strong>Format:</strong> {@code <userId>.<expiryEpochSeconds>.<mac>}, with
 * the MAC Base64url-encoded without padding so the whole thing is URL-safe and
 * survives an email client. The user id is in the clear -- the token is not
 * encrypted, only authenticated -- which reveals a database id to anyone
 * holding a link to their own account. That buys nothing without the key, and
 * the alternative is encryption and a second key to manage for the sake of a
 * sequential integer.
 */
@Service
public class SignedTokenService {

    /**
     * HMAC-SHA256, not {@code SHA256(key || message)}. Hand-rolled
     * hash-then-concatenate is where length-extension attacks come from; HMAC
     * is the construction that is actually proven, and the JDK ships it.
     */
    private static final String ALGORITHM = "HmacSHA256";

    private static final char SEPARATOR = '.';

    /** How many dot-separated fields a token has: id, expiry, MAC. */
    private static final int FIELDS = 3;

    private final SecretKeySpec key;
    private final Clock clock;

    /**
     * @param clock injected rather than {@code Instant.now()} inside the
     *              methods (CLAUDE.md), which is what lets a test issue a token
     *              and then watch it expire without sleeping.
     */
    SignedTokenService(SignedTokenProperties properties, Clock clock) {
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.clock = clock;
    }

    /**
     * Issues a token for one user and one purpose, expiring after that
     * purpose's lifetime.
     *
     * @return the token, to be put in a URL. Secret in the sense that anyone
     *         holding it can act as this user for this one purpose, so it
     *         belongs in a link and never in a log line.
     */
    public String issue(long userId, TokenPurpose purpose) {
        String id = Long.toString(userId);
        String expiresAt = Long.toString(clock.instant().plus(purpose.ttl()).getEpochSecond());
        String mac = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(purpose, id, expiresAt));
        return id + SEPARATOR + expiresAt + SEPARATOR + mac;
    }

    /**
     * Verifies a token presented for a given purpose.
     *
     * <p>The purpose is supplied by the caller -- the endpoint knows what kind
     * of link it serves -- rather than read out of the token. A purpose read
     * from the token would be a field an attacker controls before it is a field
     * that was checked; passing it in means a verification token offered to the
     * unsubscribe endpoint simply fails to verify, with no branch to get wrong.
     *
     * <p>Signature first, expiry second. Reversing them would mean deciding
     * something about a token on the strength of an expiry nobody had yet shown
     * to be the signed one, and it is also what keeps {@link
     * TokenVerification.Expired} honest: it is only ever returned for a token
     * this application really issued.
     */
    public TokenVerification verify(String token, TokenPurpose purpose) {
        if (token == null) {
            return new TokenVerification.Invalid();
        }
        // Limit -1 so that trailing empty fields are kept and counted: with the
        // default limit "5.123." splits into two fields and would get past a
        // length check written for three.
        String[] fields = token.split("\\" + SEPARATOR, -1);
        if (fields.length != FIELDS) {
            return new TokenVerification.Invalid();
        }
        String id = fields[0];
        String expiresAt = fields[1];

        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(fields[2]);
        } catch (IllegalArgumentException notBase64) {
            return new TokenVerification.Invalid();
        }

        // MessageDigest.isEqual, never String.equals or Arrays.equals on the
        // decoded bytes: both return at the first differing byte, and the time
        // that takes is a measurement of how much of a guess was right. isEqual
        // is documented as not short-circuiting for exactly this use. It does
        // return early on a length mismatch, which leaks nothing -- the length
        // of a SHA-256 MAC is public.
        if (!MessageDigest.isEqual(sign(purpose, id, expiresAt), presented)) {
            return new TokenVerification.Invalid();
        }

        // Authentic from here down: these are the bytes this application signed.
        long userId;
        long expiry;
        try {
            userId = Long.parseLong(id);
            expiry = Long.parseLong(expiresAt);
        } catch (NumberFormatException unreachable) {
            // Only reachable if something non-numeric was signed with this key,
            // which issue() cannot do. Invalid rather than a throw: a token
            // that cannot be read is a token that does not verify.
            return new TokenVerification.Invalid();
        }

        if (!clock.instant().isBefore(Instant.ofEpochSecond(expiry))) {
            return new TokenVerification.Expired();
        }
        return new TokenVerification.Valid(userId);
    }

    /**
     * The signed string: {@code PURPOSE:userId:expiryEpochSeconds}.
     *
     * <p>Signed as text, from the same strings the token carries, so there is
     * exactly one valid token per user, purpose and expiry. Re-deriving it from
     * parsed numbers instead would make {@code +5.…} and {@code 05.…} verify as
     * aliases of user 5's token -- harmless here, but "harmless" is a judgement
     * this does not have to make.
     *
     * <p>The colons are unambiguous because none of the three fields can
     * contain one: an enum constant is a Java identifier, and the other two are
     * digits. That is the property a delimiter needs -- without it,
     * {@code ("A", "BC")} and {@code ("AB", "C")} sign identically and two
     * different claims share one signature.
     */
    private byte[] sign(TokenPurpose purpose, String userId, String expiresAt) {
        String payload = purpose.name() + ':' + userId + ':' + expiresAt;
        try {
            // A new Mac per call. Mac holds the running state of one
            // computation and is not thread-safe, so a single instance as a
            // field of this singleton would corrupt signatures under concurrent
            // requests -- intermittently, and in a way a single-threaded test
            // never shows. The SecretKeySpec is immutable and is the part worth
            // keeping.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 is required of every Java platform, and the key is
            // non-empty by construction (SignedTokenProperties). Neither
            // exception is a condition a caller could do anything about.
            throw new IllegalStateException("HMAC-SHA256 unavailable or key rejected", impossible);
        }
    }
}
