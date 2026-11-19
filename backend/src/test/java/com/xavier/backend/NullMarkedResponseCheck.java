package com.xavier.backend;

import com.xavier.backend.config.NullMarkedRecords;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE-F28, test-only (every servlet context of the test JVM, {@link NullMarkedResponsesTestAutoConfiguration}): fails a
 * response whose {@code @NullMarked} record holds a null in a component not marked {@code @Nullable}, since the
 * contract tells clients that component is never null. Every HTTP test is so a check of the annotations. It lives in
 * the root package, which belongs to no module, so module tests keep it (see {@link StoreScopeTestAutoConfiguration}).
 * A tree documented as a record ({@code @Schema(implementation = …)}, the POS catalog's stored snapshot) is checked
 * as it.
 */
@ControllerAdvice
class NullMarkedResponseCheck implements ResponseBodyAdvice<Object> {

    private final Supplier<JsonMapper> json;

    NullMarkedResponseCheck(Supplier<JsonMapper> json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public @Nullable Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType,
                                            MediaType selectedContentType,
                                            Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                            ServerHttpRequest request, ServerHttpResponse response) {
        check(body);
        return body;
    }

    void check(@Nullable Object body) {
        if (body != null) {
            check(body, body.getClass().getSimpleName());
        }
    }

    private void check(@Nullable Object value, String path) {
        if (value instanceof Iterable<?> elements) {
            int index = 0;
            for (Object element : elements) {
                check(element, path + "[" + index++ + "]");
            }
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, element) -> check(element, path + "[" + key + "]"));
        } else if (value != null && value.getClass().isRecord()) {
            checkRecord(value, path);
        }
    }

    private void checkRecord(Object record, String path) {
        boolean nullMarked = NullMarkedRecords.isNullMarked(record.getClass());
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            String at = path + "." + component.getName();
            Object value = read(record, component);
            if (value == null && nullMarked && !NullMarkedRecords.isNullable(component)) {
                throw new IllegalStateException(record.getClass().getSimpleName() + "." + component.getName()
                        + " is null in a response (" + at + "), but the contract says it never is: mark the component"
                        + " @Nullable or keep it set");
            }
            Schema documented = component.getAccessor().getAnnotation(Schema.class);
            if (value instanceof JsonNode tree && documented != null && documented.implementation().isRecord()) {
                value = json.get().treeToValue(tree, documented.implementation());
            }
            check(value, at);
        }
    }

    private static @Nullable Object read(Object record, RecordComponent component) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component, e);
        }
    }
}
