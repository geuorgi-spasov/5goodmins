package com.fivegoodmins.fivegoodmins.shared.token;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the application refuses to start without a signing key.
 *
 * <p>This needs a context of its own, built from nothing, because
 * src/test/resources/application.properties supplies the key for the rest of
 * the suite -- so the guarantee is invisible in every other test here. A
 * promise satisfied by the test environment is not a promise that has been
 * checked.
 *
 * <p>ApplicationContextRunner rather than @SpringBootTest: the thing under test
 * is a startup failure, and @SpringBootTest expressing that means a test that
 * passes when the context cannot be built, which is also how it passes when
 * something unrelated breaks.
 */
class SignedTokenPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindSignedTokenProperties.class);

    @Test
    void refuseToStartWithNoSecretConfigured() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                // The failure has to name the property, or it sends whoever
                // hits it looking in the wrong place -- the mistake the absent
                // datasource default was meant to stop repeating.
                //
                // The whole trace, not getMessage(): the top-level
                // ConfigurationPropertiesBindException names only the
                // app.token prefix, and "secret" is in the
                // BindValidationException underneath it. The chain is what
                // Boot prints at startup, so it is the chain that has to carry
                // the answer -- measured here rather than assumed, because the
                // first version of this test asserted the message and passed
                // for the wrong reason only by luck.
                .hasStackTraceContaining("app.token.secret"));
    }

    @Test
    void refuseToStartWithASecretShorterThanThirtyTwoCharacters() {
        runner.withPropertyValues("app.token.secret=too-short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refuseToStartWithABlankSecret() {
        // Distinct from absent: a key set to empty or to whitespace is a
        // deployment that believes it configured one.
        runner.withPropertyValues("app.token.secret=                                   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindASecretOfAcceptableLength() {
        runner.withPropertyValues("app.token.secret=a-perfectly-adequate-key-of-32-chars")
                .run(context -> assertThat(context)
                        .hasSingleBean(SignedTokenProperties.class)
                        .getBean(SignedTokenProperties.class)
                        .extracting(SignedTokenProperties::secret)
                        .isEqualTo("a-perfectly-adequate-key-of-32-chars"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SignedTokenProperties.class)
    static class BindSignedTokenProperties {
    }
}
