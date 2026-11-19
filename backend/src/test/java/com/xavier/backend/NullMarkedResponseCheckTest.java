package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NullMarkedResponseCheckTest {

    @NullMarked
    record Strict(String name, @Nullable String note, List<Inner> inners) {
    }

    @NullMarked
    record Inner(String code) {
    }

    record Loose(String name) {
    }

    @NullMarked
    record Stored(@Schema(implementation = Inner.class) JsonNode document) {
    }

    private final JsonMapper json = JsonMapper.shared();
    private final NullMarkedResponseCheck check = new NullMarkedResponseCheck(() -> json);

    @Test
    void aNullComponentNotMarkedNullableFailsTheResponse() {
        assertThatThrownBy(() -> check.check(new Strict(null, "n", List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Strict.name");
    }

    @Test
    void aNullableComponentMayBeNull() {
        assertThatCode(() -> check.check(new Strict("a", null, List.of()))).doesNotThrowAnyException();
    }

    @Test
    void recordsInListsAndMapsAreChecked() {
        assertThatThrownBy(() -> check.check(Map.of("k", new Strict("a", null, List.of(new Inner(null))))))
                .hasMessageContaining("Inner.code").hasMessageContaining("[k].inners[0].code");
    }

    @Test
    void aRecordOutsideANullMarkedScopePromisesNothing() {
        assertThatCode(() -> check.check(new Loose(null))).doesNotThrowAnyException();
    }

    @Test
    void aTreeDocumentedAsARecordIsCheckedAsThatRecord() {
        assertThatThrownBy(() -> check.check(new Stored(json.readTree("{\"code\":null}"))))
                .hasMessageContaining("Stored.document.code");
    }
}
