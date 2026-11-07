package com.xavier.backend.promotions.loyalty.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnResult;
import com.xavier.backend.promotions.LoyaltyBalance;
import com.xavier.backend.promotions.RedeemCommand;
import com.xavier.backend.promotions.RedemptionResult;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
import com.xavier.backend.promotions.loyalty.model.EntryKind;
import com.xavier.backend.promotions.loyalty.model.Earning;
import com.xavier.backend.promotions.loyalty.model.LoyaltyAccount;
import com.xavier.backend.promotions.loyalty.model.LoyaltyPointEntry;
import com.xavier.backend.promotions.loyalty.model.LoyaltyProgramVersion;
import com.xavier.backend.promotions.loyalty.model.LoyaltyReward;
import com.xavier.backend.promotions.loyalty.model.ProgramStatus;
import com.xavier.backend.promotions.loyalty.model.SourceType;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyAccountRepository;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyPointEntryRepository;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyProgramVersionRepository;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyRewardRepository;
import com.xavier.backend.promotions.loyalty.repository.PointLot;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The points ledger behind {@link com.xavier.backend.promotions.LoyaltyApi} (D110–D112) and the manual adjustments.
 * Facade writes do not authorize (orders' use case already did) and join the caller's transaction. Every debit locks
 * the member's {@code loyalty_account} first and decides on the balance read under that lock, so two terminals never
 * spend the same points; it is then allocated FIFO to the lots it consumes. Every write is idempotent per its source
 * id ({@code loyalty_point_entry_source_uk}): a repeat with the same values returns what was recorded, a repeat with
 * other values is a 409 {@code LOYALTY_SOURCE_MISMATCH}.
 */
@Service
public class LoyaltyLedgerService {

    private static final String ORDER = "order";
    private static final String ORDER_LINE = "order line";

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyPointEntryRepository entries;
    private final LoyaltyProgramVersionRepository versions;
    private final LoyaltyRewardRepository rewards;
    private final LoyaltyLocks locks;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    LoyaltyLedgerService(LoyaltyAccountRepository accounts, LoyaltyPointEntryRepository entries,
                         LoyaltyProgramVersionRepository versions, LoyaltyRewardRepository rewards,
                         LoyaltyLocks locks, AccessPolicy accessPolicy, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.versions = versions;
        this.rewards = rewards;
        this.locks = locks;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * D112: account lock → (a repeat of the line returns its entry) → ACTIVE → the reward of the published version,
     * in scope → balance ≥ cost → REDEEM and its allocations.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RedemptionResult redeem(RedeemCommand cmd) {
        LoyaltyAccount account = locks.account(cmd.customerId())
                .orElseThrow(() -> LoyaltyRuleException.notMember(cmd.customerId()));
        Optional<LoyaltyPointEntry> recorded = entries.findBySourceTypeAndSourceIdAndKind(SourceType.ORDER_LINE,
                cmd.sourceLineId(), EntryKind.REDEEM);
        if (recorded.isPresent()) {
            return replay(recorded.get(), cmd);
        }
        if (!account.isActive()) {
            throw LoyaltyRuleException.suspended(cmd.customerId());
        }
        LoyaltyReward reward = applicableReward(cmd);
        requireBalance(cmd.customerId(), reward.getPointsCost());
        Money newUnitPrice = reward.priceAfter(cmd.currentUnitPrice());
        LedgerActor actor = LedgerActor.of(accessPolicy);
        LoyaltyPointEntry entry = entries.saveAndFlush(LoyaltyPointEntry.redeem(cmd, reward, newUnitPrice,
                actor.kind(), actor.id(), clock.instant()));
        allocate(entry);
        return new RedemptionResult(entry.getId(), reward.getPointsCost(), newUnitPrice);
    }

    /** The line's REDEEM given back once (REDEEM_REVERSAL); nothing when the line has no redemption. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseRedemption(UUID sourceLineId, UUID voidId) {
        Optional<LoyaltyPointEntry> redeemed = entries.findBySourceTypeAndSourceIdAndKind(SourceType.ORDER_LINE,
                sourceLineId, EntryKind.REDEEM);
        if (redeemed.isEmpty()) {
            return;
        }
        Optional<LoyaltyPointEntry> reversed = entries.findBySourceTypeAndSourceIdAndKind(SourceType.ORDER_LINE,
                sourceLineId, EntryKind.REDEEM_REVERSAL);
        if (reversed.isPresent()) {
            if (!reversed.get().getVoidId().equals(voidId)) {
                throw LoyaltyConflictException.sourceMismatch(ORDER_LINE, sourceLineId);
            }
            return;
        }
        LedgerActor actor = LedgerActor.of(accessPolicy);
        UniqueConstraints.saveAndFlush(entries, redeemed.get().reverseRedemption(voidId, actor.kind(), actor.id(),
                        clock.instant()), () -> LoyaltyConflictException.sourceConcurrent(ORDER_LINE, sourceLineId),
                LoyaltyPointEntry.SOURCE_UK, LoyaltyPointEntry.REVERSES_UK);
    }

    /**
     * D111: an ACTIVE member's order earns once under the version in force at {@code cmd.at()}. Takes no lock (an
     * insert only, D132); a concurrent earning of the same order is a 409 {@code LOYALTY_SOURCE_CONCURRENT}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EarnResult> earnForOrder(EarnCommand cmd) {
        Optional<LoyaltyPointEntry> recorded = entries.findBySourceTypeAndSourceIdAndKind(SourceType.ORDER,
                cmd.orderId(), EntryKind.EARN);
        if (recorded.isPresent()) {
            return Optional.of(replay(recorded.get(), cmd));
        }
        Optional<LoyaltyAccount> account = accounts.findById(cmd.customerId());
        if (account.isEmpty() || !account.get().isActive()) {
            return Optional.empty();
        }
        Optional<LoyaltyProgramVersion> program = versions.inForceAt(cmd.at());
        if (program.isEmpty()) {
            return Optional.empty();
        }
        Earning earning = program.get().earn(cmd.lines());
        if (earning.points() == 0) {
            return Optional.empty();
        }
        LedgerActor actor = LedgerActor.of(accessPolicy);
        LoyaltyPointEntry entry = UniqueConstraints.saveAndFlush(entries, LoyaltyPointEntry.earn(cmd.customerId(),
                        cmd.orderId(), earning.points(), program.get().getId(), earning.eligibleGross(),
                        cmd.storeId(), cmd.businessDate(), actor.kind(), actor.id(), clock.instant()),
                () -> LoyaltyConflictException.sourceConcurrent(ORDER, cmd.orderId()), LoyaltyPointEntry.SOURCE_UK);
        return Optional.of(result(entry));
    }

    /** A repeated earning returns the recorded one when the facts, read under the recorded version's rule, agree. */
    private EarnResult replay(LoyaltyPointEntry recorded, EarnCommand cmd) {
        Earning earning = versions.findById(recorded.getProgramVersionId()).orElseThrow().earn(cmd.lines());
        if (!recorded.repeats(cmd.customerId(), cmd.storeId(), cmd.businessDate(), earning.eligibleGross())) {
            throw LoyaltyConflictException.sourceMismatch(ORDER, cmd.orderId());
        }
        return result(recorded);
    }

    /** A manual adjustment on the locked account; a debit never takes the balance below zero. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoyaltyPointEntry adjust(LoyaltyAccount locked, int points, String reason, UUID storeId) {
        if (points < 0) {
            requireBalance(locked.getCustomerId(), -points);
        }
        LedgerActor actor = LedgerActor.of(accessPolicy);
        LoyaltyPointEntry entry = entries.saveAndFlush(LoyaltyPointEntry.adjustment(locked.getCustomerId(),
                UUID.randomUUID(), points, reason, storeId, actor.kind(), actor.id(), clock.instant()));
        if (entry.isDebit()) {
            allocate(entry);
        }
        return entry;
    }

    @Transactional(readOnly = true)
    public LoyaltyBalance balance(UUID customerId) {
        if (customerId == null) {
            return new LoyaltyBalance(null, false, null, 0); // findById(null) would doom a joined caller transaction
        }
        return accounts.findById(customerId)
                .map(a -> new LoyaltyBalance(customerId, true, a.getStatus().name(), entries.balance(customerId)))
                .orElseGet(() -> new LoyaltyBalance(customerId, false, null, 0));
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID customerId) {
        return customerId != null && accounts.findById(customerId).map(LoyaltyAccount::isActive).orElse(false);
    }

    private RedemptionResult replay(LoyaltyPointEntry recorded, RedeemCommand cmd) {
        LoyaltyReward redeemed = rewards.findById(recorded.getRewardId()).orElseThrow();
        if (!recorded.repeats(cmd, redeemed)) {
            throw LoyaltyConflictException.sourceMismatch(ORDER_LINE, cmd.sourceLineId());
        }
        return new RedemptionResult(recorded.getId(), -recorded.getPoints(), recorded.newUnitPrice());
    }

    private LoyaltyReward applicableReward(RedeemCommand cmd) {
        LoyaltyProgramVersion program = versions.findByStatus(ProgramStatus.PUBLISHED)
                .orElseThrow(() -> LoyaltyRuleException.rewardNotApplicable(cmd.rewardCode(),
                        "no loyalty program is published"));
        LoyaltyReward reward = rewards.findByVersionIdAndCode(program.getId(), cmd.rewardCode())
                .orElseThrow(() -> LoyaltyRuleException.rewardNotApplicable(cmd.rewardCode(),
                        "the published program has no such reward"));
        if (!reward.covers(cmd.itemId(), cmd.familyId())) {
            throw LoyaltyRuleException.rewardNotApplicable(cmd.rewardCode(), "the item is outside its scope");
        }
        if (cmd.currentUnitPrice().amount().signum() <= 0) {
            throw LoyaltyRuleException.rewardNotApplicable(cmd.rewardCode(), "the unit is already free");
        }
        return reward;
    }

    private void requireBalance(UUID customerId, int required) {
        long balance = entries.balance(customerId);
        if (balance < required) {
            throw LoyaltyRuleException.insufficientPoints(balance, required);
        }
    }

    /**
     * FIFO: the oldest lots first, until the debit's points are covered (the caller checked the balance). Only lots
     * up to the debit's {@code occurred_at} count (the deferred trigger refuses a later lot). That is safe with one
     * backend per installation (one clock); several instances with skewed clocks could see a committed lot stamped
     * after the debit and leave it out, so a debit could then find too few points and fail.
     */
    private void allocate(LoyaltyPointEntry debit) {
        int left = -debit.getPoints();
        for (PointLot lot : entries.openLots(debit.getCustomerId(), debit.getOccurredAt())) {
            if (left == 0) {
                break;
            }
            int taken = Math.min(left, lot.getRemaining());
            entries.allocate(debit.getId(), lot.getEntryId(), taken);
            left -= taken;
        }
        if (left > 0) {
            throw new IllegalStateException("Debit " + debit.getId() + " has " + left + " points no lot covers");
        }
    }

    private static EarnResult result(LoyaltyPointEntry entry) {
        return new EarnResult(entry.getId(), entry.getPoints(), entry.getProgramVersionId(), entry.eligibleGross());
    }
}
