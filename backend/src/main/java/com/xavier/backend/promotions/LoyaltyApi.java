package com.xavier.backend.promotions;

import com.xavier.backend.promotions.loyalty.service.LoyaltyLedgerService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What orders asks loyalty for (D110–D112, D132). Reads are {@code readOnly}; writes are {@code MANDATORY}, so orders
 * composes them in its own transaction (a reward in the discount transaction, its reversal in the void transaction,
 * the earning in the close). Nothing here authorizes: the calling use case already did. Every write is idempotent per
 * its source id (the order for an earning, the line for a redemption and its reversal): a repeat with the same values
 * returns what was recorded and adds nothing; a repeat with other values is a 409 {@code LOYALTY_SOURCE_MISMATCH}.
 *
 * <p>Errors of {@link #redeem}: a 422 with a stable code, {@code LOYALTY_NOT_MEMBER}, {@code LOYALTY_ACCOUNT_SUSPENDED},
 * {@code LOYALTY_INSUFFICIENT_POINTS} (properties {@code balance}, {@code required}) or
 * {@code LOYALTY_REWARD_NOT_APPLICABLE}. Amounts are in the company currency (another currency is an
 * {@link IllegalArgumentException}).
 */
@Service
public class LoyaltyApi {

    private final LoyaltyLedgerService ledger;

    LoyaltyApi(LoyaltyLedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Redeems the reward {@code cmd.rewardCode()} of the published program on one unit of {@code cmd.sourceLineId()}:
     * locks the member's {@code loyalty_account FOR UPDATE} (D132: after {@code order_header} and
     * {@code credit_account}), checks it is ACTIVE, the reward applies to the line and the balance covers its cost,
     * then records a REDEEM allocated FIFO to the oldest points. Returns the unit price after the reward.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RedemptionResult redeem(RedeemCommand cmd) {
        return ledger.redeem(cmd);
    }

    /** The line's redemption given back (REDEEM_REVERSAL), once; nothing when the line has no redemption. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseRedemption(UUID sourceLineId, UUID voidId) {
        ledger.reverseRedemption(sourceLineId, voidId);
    }

    /**
     * The order's EARN entry under the program version in force at {@code cmd.at()}: ⌊eligible gross ÷ spend unit⌋ ×
     * points per unit, over the lines in the version's scope that were not rewarded (P45). Empty when the customer
     * has no ACTIVE account, no version is in force, or the order earns 0 points. Once per order: a resumed close
     * gets the same entry back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EarnResult> earnForOrder(EarnCommand cmd) {
        return ledger.earnForOrder(cmd);
    }

    /** Enrollment, status and the balance (Σ of the ledger); not enrolled → {@code enrolled = false}, 0 points. */
    @Transactional(readOnly = true)
    public LoyaltyBalance balance(UUID customerId) {
        return ledger.balance(customerId);
    }

    /** True when the customer has an ACTIVE loyalty account (members-only promotions, earning). */
    @Transactional(readOnly = true)
    public boolean isMember(UUID customerId) {
        return ledger.isMember(customerId);
    }
}
