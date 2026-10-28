package com.fivegoodmins.fivegoodmins.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What page 6 of the frontend brief posts, and the only shape of registration
 * input the controller accepts (ADR-013: the entity never reaches the view).
 *
 * <p><strong>A JavaBean rather than a record, which is against the grain of
 * everything else in this package and is not a preference.</strong>
 * Thymeleaf's {@code th:field} reads the form-backing object through Spring's
 * {@code BeanWrapper}, which resolves properties through
 * {@code java.beans.Introspector} -- and an {@code Introspector} does not
 * recognise a record's {@code email()} accessor as a getter for
 * {@code email}. Spring binds <em>into</em> a record happily enough through
 * its canonical constructor; it is redisplaying one, after validation failed,
 * that does not work. Since redisplaying the form with the user's values still
 * in it is most of what this class is for, it is a bean.
 *
 * <p><strong>The limits here are the schema's, restated for a human.</strong>
 * {@code RegistrationService} checks the same numbers and throws
 * {@code IllegalArgumentException}; that is a backstop against a bug in a
 * caller, not the user-facing check, and nobody should ever see it. These are
 * the user-facing ones, and every message is a bundle key rather than a string
 * (ADR-008) -- see {@code ValidationConfig} for what makes {@code {...}} here
 * resolve against {@code messages_bg} / {@code messages_en} rather than
 * Hibernate Validator's own catalogue.
 *
 * <p><strong>Two checks are deliberately not here</strong>, and both are in
 * {@code RegistrationController} instead: the password's 72-<em>byte</em>
 * ceiling, which {@code @Size} cannot express because it counts characters and
 * Cyrillic costs two each, and the confirmation matching the password, which
 * is a relationship between two fields rather than a property of either. Both
 * are rejected onto the field a reader would look at.
 *
 * <p><strong>{@code @Size} therefore carries no {@code max}, and that is the
 * point rather than an omission.</strong> With one, a 73-character Latin
 * password tripped the annotation and the byte check both, and the form
 * rendered two error messages under one field -- one of which said "between 8
 * and 72 characters", which is not the real ceiling for the users this site is
 * for. The byte check owns the ceiling alone: one rule, one message, and the
 * message is the true one.
 */
public class RegistrationForm {

    @NotBlank(message = "{validation.email.required}")
    @Email(message = "{validation.email.format}")
    @Size(max = 254, message = "{validation.email.tooLong}")
    private String email;

    /**
     * Eight characters is the floor, and there is no composition rule -- no
     * digit, no symbol, no mixed case. Those push people towards
     * {@code Password1!} and away from a passphrase, and the length ceiling
     * below is the only hard constraint BCrypt actually imposes.
     */
    @NotBlank(message = "{validation.password.required}")
    @Size(min = 8, message = "{validation.password.tooShort}")
    private String password;

    @NotBlank(message = "{validation.password.confirmationRequired}")
    private String passwordConfirmation;

    @NotBlank(message = "{validation.displayName.required}")
    @Size(max = 80, message = "{validation.displayName.tooLong}")
    private String displayName;

    /**
     * GDPR consent, and the one field with no column behind it: the account
     * cannot exist without it, so storing "they agreed" alongside every row
     * would be storing a constant. What it is evidence of lives in the
     * privacy policy's own version history, not here.
     */
    @AssertTrue(message = "{validation.privacy.required}")
    private boolean privacyAccepted;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordConfirmation() {
        return passwordConfirmation;
    }

    public void setPasswordConfirmation(String passwordConfirmation) {
        this.passwordConfirmation = passwordConfirmation;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isPrivacyAccepted() {
        return privacyAccepted;
    }

    public void setPrivacyAccepted(boolean privacyAccepted) {
        this.privacyAccepted = privacyAccepted;
    }
}
