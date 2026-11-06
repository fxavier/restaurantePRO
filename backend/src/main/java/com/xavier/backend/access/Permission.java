package com.xavier.backend.access;

import java.util.Objects;
import java.util.UUID;

/**
 * Code-defined capabilities (D27). Roles (data, S2) are named sets of these. Later runs append
 * constants; never rename or remove one (role data refers to them by name).
 *
 * <p>Granted-authority format (the contract between login in S2 and {@link AccessPolicy}):
 * {@code PERM_<NAME>} = installation-wide, {@code PERM_<NAME>@<storeId>} = only in that store.
 */
public enum Permission {
    COMPANY_MANAGE,
    STORE_MANAGE,
    TERMINAL_MANAGE,
    USER_MANAGE,
    ROLE_MANAGE,
    EMPLOYEE_MANAGE,
    DINING_MANAGE,
    DINING_VIEW,
    CATALOG_MANAGE,
    CATALOG_PUBLISH,
    CATALOG_VIEW,
    AUDIT_VIEW,
    /** Actuator metrics/info/prometheus on the management port (D33). */
    SYSTEM_MONITOR,
    // R2 (D62). Granted by V3005__identity_r2_permissions.sql.
    /** Read orders and the table map with consumption. */
    ORDER_VIEW,
    /** Open a table or order, edit drafts, confirm rounds, print the subtotal. */
    ORDER_EDIT,
    /** Request a void (with an approver). */
    ORDER_VOID,
    /** Be the approver of a void. */
    ORDER_VOID_APPROVE,
    /** Transfers, merge, table move. */
    ORDER_TRANSFER,
    /** Cancel an empty order. */
    ORDER_CANCEL,
    /** Lock and unlock a table. */
    TABLE_LOCK,
    /** Print jobs and their failures. */
    PRODUCTION_VIEW,
    /** Retry, acknowledge UNKNOWN, reprint a ticket. */
    PRODUCTION_OPERATE,
    /** Printers, routes, modes, test print. */
    PRODUCTION_MANAGE,
    /** Set a printer to IGNORE. */
    PRODUCTION_SUPPRESS,
    /** Create, confirm or cancel payment attempts; close an order; issue its document. */
    PAYMENT_COLLECT,
    /** Resolve or reclassify an UNKNOWN attempt (with an approver). */
    PAYMENT_RESOLVE,
    /** Payment methods. */
    PAYMENT_METHOD_MANAGE,
    /** Own cash session, cash in/out, session close, closing draft and submit. */
    CASH_OPERATE,
    /** Authorized expense. */
    CASH_EXPENSE,
    /** Sessions, days and closings (read). */
    CASH_VIEW,
    /** Operational day close. */
    CASH_DAY_CLOSE,
    /** Approve or reject a closing; financial close; be the day-reopen approver. */
    CASH_CLOSING_APPROVE,
    /** Cash registers. */
    CASH_REGISTER_MANAGE,
    /** Fiscal documents (read). */
    FISCAL_DOCUMENT_VIEW,
    /** Reprint a document as a copy. */
    FISCAL_DOCUMENT_REPRINT,
    /** Fiscal series. */
    FISCAL_MANAGE,
    // R3 (D92). Granted by V3010__identity_r3_permissions.sql.
    /** Create and cancel bill partitions. */
    BILL_SPLIT,
    /** Search and view customers, their statement and aging. Company-wide (P38): checked with requireInAnyStore. */
    CUSTOMER_VIEW,
    /** Create and edit customers, contacts and addresses. */
    CUSTOMER_MANAGE,
    /** Credit limit changes, credit block and unblock. */
    CREDIT_LIMIT_MANAGE,
    /** Credit charges and their cancel. */
    CREDIT_SALE,
    /** Request a refund. */
    PAYMENT_REFUND,
    /** Be the approver of a refund. */
    PAYMENT_REFUND_APPROVE,
    /** Adjustments on closed cash sessions. */
    CASH_ADJUST,
    /** Reopen a financially closed day (with an approver). */
    CASH_DAY_REOPEN,
    /** Close an abandoned cash session (P22). */
    CASH_SESSION_FORCE_CLOSE,
    /** Reconciliation batches, entries, controls and the in-doubt list (read). */
    RECONCILIATION_VIEW,
    /** Import statements, resolve reconciliation entries. */
    RECONCILIATION_MANAGE,
    /** Reports. */
    REPORT_VIEW,
    /** Report CSV export (audited). */
    REPORT_EXPORT,
    /** Rebuild the reporting projections. */
    REPORT_REBUILD,
    /** Audit CSV export (audited). */
    AUDIT_EXPORT,
    /** Support access grants. */
    SUPPORT_ACCESS_MANAGE;

    private static final String PREFIX = "PERM_";

    /** Installation-wide authority, e.g. {@code PERM_CATALOG_PUBLISH}. */
    public String authority() {
        return PREFIX + name();
    }

    /** Store-scoped authority, e.g. {@code PERM_CATALOG_PUBLISH@0190...}. */
    public String authority(UUID storeId) {
        return authority() + "@" + Objects.requireNonNull(storeId, "storeId");
    }
}
