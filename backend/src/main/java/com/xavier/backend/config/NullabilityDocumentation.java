package com.xavier.backend.config;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * FE-F28: the contract says what a {@code @NullMarked} record promises ({@link NullMarkedRecords}), so the generated
 * client types neither widen every field to optional nor hide a null. Each component is {@code required} unless a
 * stored document may lack it; a {@code @Nullable} one also allows {@code null} (OpenAPI 3.1: {@code "null"} joins its
 * {@code type}; a reference or an enum becomes {@code oneOf} it and {@code null}). Records outside a
 * {@code @NullMarked} scope keep springdoc's defaults ({@code required} from Bean Validation only).
 */
@Component
class NullabilityDocumentation implements ModelConverter {

    private static final String NULL = "null";

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (resolved == null || javaType == null || !NullMarkedRecords.isNullMarked(javaType.getRawClass())) {
            return resolved;
        }
        Schema<?> model = resolved.get$ref() == null ? resolved
                : context.getDefinedModels().get(resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1));
        if (model != null && model.getProperties() != null) {
            document(model, javaType.getRawClass());
        }
        return resolved;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void document(Schema model, Class<?> record) {
        Map<String, Schema> properties = model.getProperties();
        for (RecordComponent component : record.getRecordComponents()) {
            Schema property = properties.get(component.getName());
            if (property == null) {
                continue;
            }
            if (!NullMarkedRecords.mayBeAbsent(component)
                    && (model.getRequired() == null || !model.getRequired().contains(component.getName()))) {
                model.addRequiredItem(component.getName());
            }
            if (NullMarkedRecords.isNullable(component)) {
                properties.put(component.getName(), nullable(property));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (allowsNull(property)) {
            return property;
        }
        if (property.get$ref() != null || property.getEnum() != null) {
            return new Schema<>().oneOf(List.of(property, new Schema<>().types(Set.of(NULL))));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add(NULL);
        property.setTypes(types);
        return property;
    }

    @SuppressWarnings("rawtypes")
    private static boolean allowsNull(Schema property) {
        if (property.getTypes() != null && property.getTypes().contains(NULL)) {
            return true;
        }
        List<Schema> alternatives = property.getOneOf();
        return alternatives != null && alternatives.stream()
                .anyMatch(alternative -> alternative.getTypes() != null && alternative.getTypes().contains(NULL));
    }
}
