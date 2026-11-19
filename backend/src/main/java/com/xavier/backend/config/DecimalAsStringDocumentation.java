package com.xavier.backend.config;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import java.util.Iterator;
import org.springframework.stereotype.Component;

/**
 * FE-F33: Jackson writes a BigDecimal as a decimal string ({@code shared.web.DecimalsAsStrings}), so the contract
 * types it {@code string} with format {@code decimal} and the frontend's generated types say string (ADR 0042). A new
 * schema per property, so bean-validation bounds springdoc adds to one property never leak into another.
 */
@Component
class DecimalAsStringDocumentation implements ModelConverter {

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null && BigDecimal.class.equals(javaType.getRawClass())) {
            return new StringSchema().format("decimal");
        }
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }
}
