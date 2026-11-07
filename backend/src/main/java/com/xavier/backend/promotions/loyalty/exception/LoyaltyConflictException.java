package com.xavier.backend.promotions.loyalty.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A loyalty state conflict: 409 with a stable {@code code} (PRD R4 conventions). */
public class LoyaltyConflictException extends ConflictException {

    public LoyaltyConflictException(String code, String message) {
        super(code, message);
    }

    /** Only a DRAFT version is edited or published (D111). */
    public static LoyaltyConflictException programNotDraft(UUID versionId, Object status, String action) {
        return new LoyaltyConflictException("LOYALTY_PROGRAM_NOT_DRAFT",
                "The loyalty program version " + versionId + " is " + status + " and cannot " + action + ".");
    }

    /** Two versions created or published at the same time; the loser retries. */
    public static LoyaltyConflictException programConcurrent() {
        return new LoyaltyConflictException("LOYALTY_PROGRAM_CONCURRENT",
                "Another loyalty program version was created or published at the same time; retry.");
    }

    /** {@code UNIQUE(version_id, code)}. */
    public static LoyaltyConflictException rewardCodeTaken(String code) {
        return new LoyaltyConflictException("LOYALTY_REWARD_CODE_TAKEN",
                "The program version already has a reward " + code + ".");
    }

    public static LoyaltyConflictException alreadyEnrolled(UUID customerId) {
        return new LoyaltyConflictException("LOYALTY_ALREADY_ENROLLED",
                "Customer " + customerId + " is already a loyalty member.");
    }

    /** ACTIVE ↔ SUSPENDED only: suspending a suspended account or resuming an active one. */
    public static LoyaltyConflictException accountState(UUID customerId, Object status, String action) {
        return new LoyaltyConflictException("LOYALTY_ACCOUNT_STATE",
                "The loyalty account of customer " + customerId + " is " + status + " and cannot " + action + ".");
    }

    /**
     * A repeated call for a source already recorded ({@code source} = order, order line) that does not repeat it:
     * another customer, reward, price, void or gross. Nothing is changed.
     */
    public static LoyaltyConflictException sourceMismatch(String source, UUID sourceId) {
        return new LoyaltyConflictException("LOYALTY_SOURCE_MISMATCH",
                "The " + source + " " + sourceId + " is already recorded in the loyalty ledger with other values.");
    }

    /** Two writes for the same source at once; the loser retries and then sees the first one's entry. */
    public static LoyaltyConflictException sourceConcurrent(String source, UUID sourceId) {
        return new LoyaltyConflictException("LOYALTY_SOURCE_CONCURRENT",
                "The " + source + " " + sourceId + " was recorded in the loyalty ledger at the same time; retry.");
    }
}
