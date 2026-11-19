package com.xavier.backend.shared.web;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * FE-F33: every BigDecimal (Money amounts, quantities, rates) is written as a JSON string holding the plain decimal,
 * scale kept and never an exponent ("0.30", "1234567890123456.78"). A JSON number loses precision in JavaScript
 * beyond ~15 significant digits and amounts allow 17. Reading is unchanged: Jackson accepts a string or a number, so
 * clients sending numbers and records stored with numbers keep working. Spring Boot registers this module on the
 * application's {@code JsonMapper}, so it applies to HTTP and to everything that mapper writes.
 */
@Component
public class DecimalsAsStrings extends SimpleModule {

    public DecimalsAsStrings() {
        super(DecimalsAsStrings.class.getSimpleName());
        addSerializer(BigDecimal.class, new PlainDecimalAsString());
    }

    private static final class PlainDecimalAsString extends StdSerializer<BigDecimal> {

        PlainDecimalAsString() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.toPlainString());
        }
    }
}
