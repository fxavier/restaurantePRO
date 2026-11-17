package com.xavier.bff;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One request as a stub server received it. Header names are lower-cased. */
record RecordedRequest(String method, URI uri, Map<String, List<String>> headers, String body) {

    String header(String name) {
        List<String> values = headers.get(name.toLowerCase());
        return values == null ? null : String.join(",", values);
    }

    Map<String, String> form() {
        return parse(body);
    }

    static Map<String, String> parse(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }
}
