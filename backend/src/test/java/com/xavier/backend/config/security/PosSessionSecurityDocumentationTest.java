package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

/** FE-F06: a later customiser (or springdoc itself) may add to an operation's security list; it must not throw. */
class PosSessionSecurityDocumentationTest {

    @Test
    void theSecurityListOfAPosOperationStaysMutable() {
        Operation catalog = new Operation();
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/api/v1/pos/catalog",
                new PathItem().get(catalog)));

        new PosSessionSecurityDocumentation().customise(openApi);
        catalog.addSecurityItem(new SecurityRequirement().addList("another"));

        assertThat(catalog.getSecurity()).hasSize(2);
    }
}
