package com.xavier.backend.production.model;

import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stored form of STYLED_V1 content (D155): a JSON array of {@code {"t": text, "s": style, "a": align}},
 * {@code {"rule": true}} and {@code {"l": left, "r": right, "s": style}}. Tickets are append-only, so the format
 * never changes in place; a new shape is a new format.
 */
public final class StyledContent {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StyledContent() {
    }

    public static String toJson(List<PrintLine> lines) {
        ArrayNode array = JSON.createArrayNode();
        for (PrintLine line : lines) {
            ObjectNode node = array.addObject();
            switch (line) {
                case PrintLine.Text text -> node.put("t", text.text()).put("s", text.style().name())
                        .put("a", text.align().name());
                case PrintLine.Rule rule -> node.put("rule", true);
                case PrintLine.LeftRight leftRight -> node.put("l", leftRight.left()).put("r", leftRight.right())
                        .put("s", leftRight.style().name());
            }
        }
        return JSON.writeValueAsString(array);
    }

    public static List<PrintLine> fromJson(String json) {
        List<PrintLine> lines = new ArrayList<>();
        for (JsonNode node : JSON.readTree(json)) {
            if (node.has("rule")) {
                lines.add(new PrintLine.Rule());
            } else if (node.has("l")) {
                lines.add(new PrintLine.LeftRight(node.get("l").asString(), node.get("r").asString(),
                        Style.valueOf(node.get("s").asString())));
            } else {
                lines.add(new PrintLine.Text(node.get("t").asString(), Style.valueOf(node.get("s").asString()),
                        Align.valueOf(node.get("a").asString())));
            }
        }
        return lines;
    }
}
