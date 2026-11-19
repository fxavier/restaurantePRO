package com.xavier.backend.config;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.Iterator;
import org.springframework.stereotype.Component;

/**
 * FE-F33: Jackson writes a BigDecimal as a decimal string ({@code shared.web.DecimalsAsStrings}), so the contract
 * types it {@code string} with format {@code decimal} and the frontend's generated types say string (ADR 0042). A new
 * schema per property, so one property's bounds never leak into another.
 *
 * <p>FE-F28: JSON Schema's {@code minimum} and {@code maximum} apply to numbers only (a validator ignores them on a
 * string), so a Bean Validation bound is documented as {@code x-minimum}, {@code x-exclusiveMinimum},
 * {@code x-maximum} or {@code x-exclusiveMaximum}: the exact bound as a decimal string, never a float.
 */
@Component
class DecimalAsStringDocumentation implements ModelConverter {

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (javaType != null && BigDecimal.class.equals(javaType.getRawClass())) {
            Schema<?> decimal = new StringSchema().format("decimal");
            if (type.getCtxAnnotations() != null) {
                for (Annotation annotation : type.getCtxAnnotations()) {
                    documentBound(decimal, annotation);
                }
            }
            return decimal;
        }
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }

    private static void documentBound(Schema<?> decimal, Annotation annotation) {
        switch (annotation) {
            case Positive ignored -> decimal.addExtension("x-exclusiveMinimum", "0");
            case PositiveOrZero ignored -> decimal.addExtension("x-minimum", "0");
            case DecimalMin min -> decimal.addExtension(min.inclusive() ? "x-minimum" : "x-exclusiveMinimum",
                    min.value());
            case DecimalMax max -> decimal.addExtension(max.inclusive() ? "x-maximum" : "x-exclusiveMaximum",
                    max.value());
            default -> {
            }
        }
    }
}
