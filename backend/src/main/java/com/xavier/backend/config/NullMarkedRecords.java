package com.xavier.backend.config;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * FE-F28: what a record's JSON promises, read from its JSpecify annotations. A record in a {@link NullMarked} scope (on
 * itself, an enclosing class or its package) writes every component (Jackson writes nulls too) and only its
 * {@link Nullable} components may be null; one whose accessor says {@code @Schema(requiredMode = NOT_REQUIRED)} may be
 * missing from a stored document written before it existed. A record outside such a scope promises nothing. Public for
 * the test guard that checks real responses against the same rule ({@code NullMarkedResponseCheck}).
 */
public final class NullMarkedRecords {

    private NullMarkedRecords() {
    }

    public static boolean isNullMarked(Class<?> type) {
        if (!type.isRecord()) {
            return false;
        }
        for (Class<?> scope = type; scope != null; scope = scope.getEnclosingClass()) {
            if (scope.isAnnotationPresent(NullMarked.class)) {
                return true;
            }
        }
        return type.getPackage().isAnnotationPresent(NullMarked.class);
    }

    public static boolean isNullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }

    public static boolean mayBeAbsent(RecordComponent component) {
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        return schema != null && schema.requiredMode() == Schema.RequiredMode.NOT_REQUIRED;
    }
}
