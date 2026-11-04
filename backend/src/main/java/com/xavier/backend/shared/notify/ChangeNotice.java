package com.xavier.backend.shared.notify;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** The SSE payload (D60): {@code {"topic":"ORDER","id":"…"}}; {@code id} is omitted when the topic has none. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeNotice(ChangeTopic topic, UUID id) {
}
