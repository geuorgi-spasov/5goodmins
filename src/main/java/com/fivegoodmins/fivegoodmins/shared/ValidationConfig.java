package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Makes Bean Validation read its messages out of the same bundles as every
 * other string (ADR-008).
 *
 * <p>Without this, {@code @NotBlank(message = "{validation.email.required}")}
 * resolves against Hibernate Validator's own {@code ValidationMessages.properties}
 * -- a second, invisible message catalogue that is not in
 * {@code messages_bg} / {@code messages_en}, is not covered by
 * {@code MessageBundlesTest}, and has no Bulgarian in it. Every form error on
 * the site would have arrived in English, from a file nobody edits.
 *
 * <p><strong>It does not fight the strict {@code MessageSource}, which is the
 * part worth checking rather than assuming.</strong> {@link MessageSourceConfig}
 * turns off locale fallback so that an unresolvable code throws, and a
 * validator that let {@code NoSuchMessageException} out while interpolating a
 * constraint message would turn a typo in a message key into a 500 on every
 * form. It does not: Spring wraps the {@code MessageSource} in a
 * {@code MessageSourceResourceBundle}, whose {@code handleGetObject} catches
 * {@code NoSuchMessageException} and returns null, so interpolation falls
 * through to Hibernate Validator's own default rather than blowing up inside
 * the validator.
 *
 * <p>What a missing key does fail is the render, further down, and loudly
 * rather than quietly -- which is the same trade {@code MessageSourceConfig}
 * makes everywhere else, and the reason {@code MessageBundlesTest} checks that
 * both bundles define the same keys. The controller tests assert the rendered
 * error text for every constraint on the registration form, so a key that
 * exists in neither bundle fails there rather than in front of a user.
 *
 * <p>Named {@code defaultValidator} because that is the name Boot's
 * {@code ValidationAutoConfiguration} uses and it is
 * {@code @ConditionalOnMissingBean}; a differently named bean would leave
 * Boot's own validator in place alongside this one, and MVC would bind to
 * whichever it found first.
 */
@Configuration(proxyBeanMethods = false)
class ValidationConfig {

    @Bean
    LocalValidatorFactoryBean defaultValidator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }
}
