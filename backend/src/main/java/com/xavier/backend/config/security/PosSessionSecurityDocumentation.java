package com.xavier.backend.config.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * {@code X-Pos-Session} is read by {@link TenantResolutionFilter}, not by a controller, so springdoc cannot see it.
 * This declares it as the {@code posSession} security scheme, required by every POS operation that needs it (every
 * POS route except the PIN login and the enrolment). A scheme, not a header parameter: the frontend's client
 * middleware adds it (ADR 0042), so the generated types must not ask every call to pass it.
 */
@Component
class PosSessionSecurityDocumentation implements OpenApiCustomizer {

    static final String SCHEME = "posSession";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSecuritySchemes(SCHEME, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name(TenantResolutionFilter.POS_SESSION)
                .description("The employee's POS session token, from the PIN login (D182)."));
        openApi.getPaths().forEach((path, item) -> {
            if (RouteFamily.of(path).filter(family -> family == RouteFamily.POS).isPresent()
                    && !RouteFamily.posSessionExempt(path)) {
                item.readOperations().forEach(PosSessionSecurityDocumentation::require);
            }
        });
    }

    /**
     * A controller that also reads the header (the logout) would otherwise document it twice, once as a parameter.
     * The list stays mutable: swagger's own {@code addSecurityItem} (another customiser, springdoc) appends to it.
     */
    private static void require(Operation operation) {
        operation.setSecurity(new ArrayList<>(List.of(new SecurityRequirement().addList(SCHEME))));
        if (operation.getParameters() != null) {
            operation.getParameters().removeIf(p -> "header".equals(p.getIn())
                    && TenantResolutionFilter.POS_SESSION.equals(p.getName()));
            if (operation.getParameters().isEmpty()) {
                operation.setParameters(null);
            }
        }
    }
}
