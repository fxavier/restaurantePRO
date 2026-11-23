package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FE-F35: every unique key on a business {@code code} compares it ignoring case ({@code upper(code)}), so "abc" and
 * "ABC" cannot coexist in the same scope; the application keeps the case the code was entered with. The exceptions
 * are listed with the reason they stay exact.
 */
class CodeKeysIgnoreCaseTest {

    /** Unique keys on a {@code code} column that deliberately compare it exactly. */
    static final Map<String, String> EXACT_KEYS = Map.of(
            "catalog.barcode_code_uk", "a barcode is scanned data, not a typed code: its case may matter",
            "inventory.stock_unit_pk", "reference data seeded by migrations, the target of foreign keys",
            "settlement.payment_method_code_uk", "the API admits only [A-Z0-9_]: no case variant can be written",
            "tenancy.tenant_code_uk", "the API admits only [a-z0-9-] and create_tenant targets the constraint by name");

    @Test
    void everyUniqueKeyOnACodeComparesItIgnoringCase() {
        List<String> exactKeys = OwnerDatabase.shared().jdbc().sql("""
                        SELECT schemaname || '.' || indexname FROM pg_indexes
                        WHERE indexdef LIKE 'CREATE UNIQUE INDEX%' AND indexdef ~ '[(, ]code[),]'
                          AND indexdef !~ 'upper\\(\\(?code[):]'
                        ORDER BY 1""")
                .query(String.class).list();

        assertThat(exactKeys).containsExactlyInAnyOrderElementsOf(EXACT_KEYS.keySet());
    }
}
