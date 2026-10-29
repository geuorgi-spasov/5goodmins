package com.fivegoodmins.fivegoodmins.shared.dev;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The living style guide (frontend brief, section 8), and through it the four
 * pieces of layout wiring a frontend contributor inherits rather than writes:
 * the taxonomy on the model, the skip link, the stylesheet and script tags,
 * and the CSRF token on htmx requests.
 *
 * <p><strong>The htmx round trip at the bottom is why this file exists.</strong>
 * ADR-002 predicts that every project loses an hour to htmx POSTs returning
 * 403, because the token has to reach them through {@code hx-headers} in the
 * layout rather than through a hidden form input. Asserting that the
 * attribute is in the markup would only prove the markup; posting with the
 * token and then without it proves which half does the work.
 *
 * <p><strong>{@code dev} profile, because the controller is
 * {@code @Profile("dev")}</strong> -- which also means this is the one test
 * that loads {@code application-dev.yaml}, and therefore the only one that
 * would notice if {@code spring.thymeleaf.prefix=file:./src/main/resources/templates/}
 * stopped resolving. That property is what makes a template edit a refresh
 * rather than a restart, and a typo in it breaks every page in development
 * while CI stays green.
 *
 * <p>The Flyway location is put back to the production list on purpose. The
 * dev profile adds {@code db/dev-seed}, whose repeatable migration inserts 31
 * actions and three users; the whole suite shares one PostgreSQL container
 * (TestcontainersConfiguration), so letting it run here would leave that
 * content in the database every other test reads. The seed is development
 * data, verified by running the application, not by this.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class StyleGuideWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final String STYLE_GUIDE = "/dev/styleguide";

    MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
    }

    @Test
    void rendersEveryVocabularyThroughTheMessageBundles() throws Exception {
        // One from each of the four lists TaxonomyModel puts on the model. If
        // the advice stops being applied, or a key is missing, this page is
        // where it shows up first -- which is the point of having it.
        mockMvc.perform(get(STYLE_GUIDE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Петиция")))
                .andExpect(content().string(containsString("Човешки права")))
                .andExpect(content().string(containsString("Стара Загора")))
                .andExpect(content().string(containsString("Чака одобрение")));
    }

    @Test
    void servesTheStyleGuideToAnAnonymousVisitor() throws Exception {
        // No login. A style guide behind authentication is a style guide the
        // contributor cannot open on the morning they start.
        mockMvc.perform(get(STYLE_GUIDE)).andExpect(status().isOk());
    }

    @Test
    void carriesTheSkipLinkTheStylesheetAndTheVendoredHtmx() throws Exception {
        mockMvc.perform(get(STYLE_GUIDE))
                .andExpect(content().string(containsString("href=\"#main\"")))
                .andExpect(content().string(containsString("id=\"main\"")))
                .andExpect(content().string(containsString("/css/main.css")))
                // Vendored, not a CDN: the Content-Security-Policy allows
                // scripts from this origin only (ADR-020).
                .andExpect(content().string(containsString("/js/htmx-2.0.11.min.js")));
    }

    @Test
    void putsTheCsrfTokenWhereHtmxWillSendItFrom() throws Exception {
        mockMvc.perform(get(STYLE_GUIDE))
                .andExpect(content().string(containsString("hx-headers")))
                .andExpect(content().string(containsString("X-CSRF-TOKEN")));
    }

    @Test
    void answersAnHtmxPostWithAFragmentWhenTheTokenIsPresent() throws Exception {
        mockMvc.perform(post(STYLE_GUIDE + "/htmx-check").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"htmx-result\"")))
                // A fragment, not a page: no <html> and no site header
                // (ADR-001). Swapping a whole page into a div is the other
                // htmx mistake.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))));
    }

    @Test
    void refusesTheSameHtmxPostWithoutTheToken() throws Exception {
        // The failure mode the attribute in the layout exists to prevent, and
        // the reason removing it is not a cosmetic change.
        mockMvc.perform(post(STYLE_GUIDE + "/htmx-check"))
                .andExpect(status().isForbidden());
    }
}
