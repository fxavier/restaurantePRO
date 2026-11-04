package com.xavier.backend.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CodedExceptionTest {

    @Test
    void anUncodedErrorKeepsItsMessageAndHasNoCodeNorProperties() {
        BusinessRuleException e = new BusinessRuleException("Currency cannot change");

        assertThat(e).hasMessage("Currency cannot change");
        assertThat(e.code()).isNull();
        assertThat(e.properties()).isEmpty();
    }

    @Test
    void aCodedErrorKeepsCodeMessageAndPropertiesInOrder() {
        ConflictException e = new ConflictException("TABLE_OCCUPIED", "Table 7 is occupied.",
                Map.of("tableId", "t-7"));

        assertThat(e).hasMessage("Table 7 is occupied.");
        assertThat(e.code()).isEqualTo("TABLE_OCCUPIED");
        assertThat(e.properties()).containsExactly(Map.entry("tableId", "t-7"));
    }

    @Test
    void aPropertyCannotShadowAProblemDetailMemberOrTheCode() {
        for (String reserved : new String[] {"code", "type", "title", "status", "detail", "instance"}) {
            assertThatThrownBy(() -> new BusinessRuleException("X", "m", Map.of(reserved, "v")))
                    .as(reserved).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aCodeIsNotBlank() {
        assertThatThrownBy(() -> new NotFoundException(" ", "m")).isInstanceOf(IllegalArgumentException.class);
    }
}
