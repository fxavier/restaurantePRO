package com.xavier.backend.production.model.dto;

import java.util.Optional;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * A field a PUT may omit (kept: {@code null}) or send as JSON {@code null} (cleared: empty), which Jackson's own
 * {@code Optional} handling does not tell apart for a record.
 */
final class ClearableIntDeserializer extends ValueDeserializer<Optional<Integer>> {

    @Override
    public Optional<Integer> deserialize(JsonParser parser, DeserializationContext context) {
        return Optional.of(context.readValue(parser, Integer.class));
    }

    @Override
    public Object getNullValue(DeserializationContext context) {
        return Optional.empty();
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
