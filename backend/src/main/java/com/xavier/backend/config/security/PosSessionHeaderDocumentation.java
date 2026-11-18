package com.xavier.backend.config.security;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import java.util.List;
import java.util.Optional;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * {@code X-Pos-Session} is read by {@link TenantResolutionFilter}, not by a controller, so springdoc cannot see it.
 * This declares it on every POS operation that needs it (every POS route except the PIN login and the enrolment), so
 * the frontend's generated client (ADR 0042) knows the POS must send it.
 */
@Component
class PosSessionHeaderDocumentation implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> {
            if (RouteFamily.of(path).filter(family -> family == RouteFamily.POS).isPresent()
                    && !RouteFamily.posSessionExempt(path)) {
                item.readOperations().forEach(PosSessionHeaderDocumentation::declare);
            }
        });
    }


    private static void declare(Operation operation) {
        boolean declared = Optional.ofNullable(operation.getParameters()).stream().flatMap(List::stream)
                .anyMatch(p -> "header".equals(p.getIn()) && TenantResolutionFilter.POS_SESSION.equals(p.getName()));
        if (!declared) {
            operation.addParametersItem(new HeaderParameter().name(TenantResolutionFilter.POS_SESSION).required(true)
                    .description("The employee's POS session token, from the PIN login (D182).")
                    .schema(new StringSchema()));
        }
    }
}
