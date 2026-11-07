package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.shared.notify.ChangeNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** D60: a publication tells the store's POS clients to re-read the catalog, once it has committed. */
@CatalogModuleTest
@Import(CatalogNoticeHttpTest.NoticeStream.class)
public class CatalogNoticeHttpTest {

    public static final String CATALOG_NOTICE = "data:{\"topic\":\"CATALOG\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void publishingNotifiesTheStoresPosAndARefusedPublishDoesNot() throws Exception {
        Publications publications = new Publications(mvc, jdbc);
        UUID store = publications.sellableStore().store();
        UUID other = new CatalogFixtures(mvc).store();
        MockHttpServletResponse stream = subscribe(store);
        MockHttpServletResponse otherStream = subscribe(other);
        UUID draft = CatalogFixtures.id(publications.createDraft(store));
        publications.publish(store, draft, UUID.randomUUID().toString(), "{}").andExpect(status().isConflict());

        publications.publishNew(store);

        eventually(() -> assertThat(content(stream)).contains(CATALOG_NOTICE));
        assertThat(Pattern.compile(Pattern.quote(CATALOG_NOTICE)).matcher(content(stream)).results().count())
                .isEqualTo(1); // the refused publication of the draft sent nothing
        assertThat(content(otherStream)).doesNotContain("CATALOG");
    }

    private MockHttpServletResponse subscribe(UUID store) throws Exception {
        return mvc.perform(get(NoticeStream.PATH + store).with(pos(store, Permission.CATALOG_VIEW))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse();
    }

    private static String content(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Notices are sent off the committing thread; poll briefly (no Awaitility on the classpath). */
    private static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    /**
     * Test-only stand-in for {@code config.NotificationController}, which a catalog module test does not boot: it
     * subscribes straight to {@link ChangeNotifier}, so the real after-commit path is what the test observes.
     */
    @RestController
    public static class NoticeStream {

        public static final String PATH = "/test-only/catalog-notices/";

        private final ChangeNotifier notifier;

        NoticeStream(ChangeNotifier notifier) {
            this.notifier = notifier;
        }

        @GetMapping(PATH + "{storeId}")
        SseEmitter subscribe(@PathVariable UUID storeId) {
            return notifier.subscribe(storeId);
        }
    }
}
