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

    /** FE-F29: a 409 without a code cannot be told apart from VERSION_CONFLICT or IDEMPOTENCY_IN_PROGRESS. */
    @Test
    void aConflictAlwaysHasACode() {
        assertThatThrownBy(() -> new ConflictException(null, "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConflictException(null, "m", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConflictException(null, "m", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** FE-F29: no constructor can make an uncoded conflict, so a new 409 cannot forget its code. */
    @Test
    void everyConflictConstructorTakesACode() {
        assertThat(ConflictException.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameters()[0].getName()).isEqualTo("code"));
    }

    @Test
    void aCodeIsNotBlank() {
        assertThatThrownBy(() -> new NotFoundException(" ", "m")).isInstanceOf(IllegalArgumentException.class);
    }
}
