package com.xavier.backend.shared.ops;

import java.util.List;

/**
 * A module's contribution to the operational alerts (D149). Implemented next to the data it reads (its own tables
 * or an in-memory gauge); {@code config} collects every bean as {@code List<AlertSource>}.
 */
public interface AlertSource {

    /**
     * The alerts active now; empty when all is well. Must be fast: it reads a gauge snapshot or runs one indexed
     * query.
     */
    List<OperationalAlert> alerts();
}
