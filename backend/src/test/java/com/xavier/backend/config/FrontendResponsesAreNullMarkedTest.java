package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.catalog.model.dto.PosCatalogResponse;
import com.xavier.backend.dining.model.dto.RoomAreaSummary;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.model.dto.TenantChoice;
import com.xavier.backend.orders.model.dto.BatchView;
import com.xavier.backend.orders.model.dto.LineView;
import com.xavier.backend.orders.model.dto.OrderSummary;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.organization.model.dto.DeviceEnrolmentResponse;
import com.xavier.backend.organization.model.dto.EnrollmentCodeResponse;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * FE-F28: the responses the frontend reads are {@code @NullMarked}, so the contract lists their components as required
 * and only the {@code @Nullable} ones as nullable. Every record they reach must be in that scope too, or its components
 * would silently fall back to "optional" in the generated types. Which components can be null is checked on the real
 * responses of every HTTP test ({@code NullMarkedResponseCheck}).
 */
class FrontendResponsesAreNullMarkedTest {

    /** The response bodies of the endpoints the POS and the BackOffice call today. */
    static final List<Class<?>> FRONTEND_RESPONSES = List.of(SessionView.class, TenantChoice.class,
            StoreSummary.class, TerminalSummary.class, EnrollmentCodeResponse.class, DeviceEnrolmentResponse.class,
            PageResponse.class, PosCatalogResponse.class, TableMap.class, RoomAreaSummary.class, OrderView.class,
            OrderSummary.class, BatchView.class, LineView.class);

    @Test
    void everyRecordTheFrontendReadsIsNullMarked() {
        Set<Class<?>> records = reachableRecords(FRONTEND_RESPONSES);

        assertThat(records).as("the walk reaches the nested records").contains(TableMap.Table.class,
                LineView.ComponentView.class, com.xavier.backend.shared.Money.class,
                com.xavier.backend.catalog.model.dto.CatalogSnapshot.MenuOption.class);
        assertThat(records.stream().filter(type -> !NullMarkedRecords.isNullMarked(type)).map(Class::getName))
                .as("records outside a @NullMarked scope").isEmpty();
    }

    private static Set<Class<?>> reachableRecords(List<Class<?>> roots) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!type.isRecord() || !seen.add(type)) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                collect(component.getGenericType(), pending);
                Schema documented = component.getAccessor().getAnnotation(Schema.class);
                if (documented != null && documented.implementation() != Void.class) {
                    pending.push(documented.implementation());
                }
            }
        }
        return seen;
    }

    private static void collect(Type type, Deque<Class<?>> pending) {
        if (type instanceof Class<?> raw) {
            pending.push(raw);
        } else if (type instanceof ParameterizedType parameterized) {
            collect(parameterized.getRawType(), pending);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, pending);
            }
        }
    }
}
