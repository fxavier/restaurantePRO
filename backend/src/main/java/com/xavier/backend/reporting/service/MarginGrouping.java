package com.xavier.backend.reporting.service;

/**
 * The margin report's groups (D131), as a key expression over each projection it reads (alias {@code t}): the sales
 * lines, the cost movements and waste shares (keyed by the item sold), and the cost variances. A variance is of a
 * stock item, not of an item sold, so by item or family it is a group of its own (no key).
 */
enum MarginGrouping {
    ITEM("t.item_id::text", "t.sale_item_id::text", ReportGroups.NONE),
    FAMILY("t.family_code", "t.family_code", ReportGroups.NONE),
    DAY("t.business_date::text", "t.business_date::text", "t.business_date::text");

    final String salesKey;
    final String costKey;
    final String varianceKey;

    MarginGrouping(String salesKey, String costKey, String varianceKey) {
        this.salesKey = salesKey;
        this.costKey = costKey;
        this.varianceKey = varianceKey;
    }
}
