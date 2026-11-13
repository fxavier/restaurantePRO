package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Store item exclusions (D146) through the BackOffice HTTP API, as a store manager of that store. */
public final class Exclusions {

    private final MockMvc mvc;

    public Exclusions(MockMvc mvc) {
        this.mvc = mvc;
    }

    public static String path(UUID store) {
        return CATALOG + "/stores/" + store + "/item-exclusions";
    }

    /** {@code reason} {@code null}: no body. */
    public ResultActions exclude(UUID store, UUID item, String reason) throws Exception {
        var request = put(path(store) + "/" + item).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf());
        if (reason != null) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"%s\"}".formatted(reason));
        }
        return mvc.perform(request);
    }

    public ResultActions include(UUID store, UUID item) throws Exception {
        return mvc.perform(delete(path(store) + "/" + item).with(inStore(store, Permission.CATALOG_MANAGE))
                .with(csrf()));
    }
}
