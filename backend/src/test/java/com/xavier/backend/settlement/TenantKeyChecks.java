package com.xavier.backend.settlement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * D179 (security rule 3) for the slices that convert a module before {@code CompositeForeignKeyCoverageTest} lists it
 * (S76 does): runs that test's own per-module check, so the rule is not copied. Remove with the reflection once the
 * module is in {@code RowLevelSecurityCoverageTest.TENANT_PROTECTED_MODULES}.
 */
public final class TenantKeyChecks {

    private TenantKeyChecks() {
    }

    /** Every foreign key and business key of the tenant tables of {@code schema} includes {@code tenant_id}. */
    public static void assertKeysIncludeTheTenant(String schema) {
        try {
            Method check = Class.forName("com.xavier.backend.architecture.CompositeForeignKeyCoverageTest")
                    .getDeclaredMethod("assertModuleKeysIncludeTheTenant", String.class);
            check.setAccessible(true);
            check.invoke(null, schema);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
