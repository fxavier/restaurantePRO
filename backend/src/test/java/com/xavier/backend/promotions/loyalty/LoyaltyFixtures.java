package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.id;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersFixtures.Pos;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.EarnResult;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.RedeemCommand;
import com.xavier.backend.promotions.RedemptionResult;
import com.xavier.backend.shared.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Program versions (BackOffice HTTP, as a program manager) and members (customers created and enrolled at a POS) for
 * loyalty tests. The program is company-wide and one version is in force at a time, so a test that needs a rule
 * publishes its own version first.
 */
@TestComponent
public class LoyaltyFixtures {

    public static final String PROGRAMS = "/api/v1/backoffice/loyalty/program-versions";
    public static final String ACCOUNTS = "/api/v1/backoffice/loyalty/accounts";

    public static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    public static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-0000000005e0");

    private final MockMvc mvc;
    private final CustomersFixtures customers;
    private final LoyaltyApi loyalty;
    private final TransactionTemplate tx;
    private final Clock clock;

    LoyaltyFixtures(MockMvc mvc, CustomersFixtures customers, LoyaltyApi loyalty, TransactionTemplate tx,
                    Clock clock) {
        this.mvc = mvc;
        this.customers = customers;
        this.loyalty = loyalty;
        this.tx = tx;
        this.clock = clock;
    }

    /** The order's earning at the clock's instant, one line of {@code gross} MZN, in its own transaction. */
    public Optional<EarnResult> earn(UUID customer, UUID order, String gross) {
        return tx.execute(status -> loyalty.earnForOrder(earnCommand(customer, order, gross)));
    }

    public EarnCommand earnCommand(UUID customer, UUID order, String gross) {
        return new EarnCommand(order, STORE, customer, DAY, clock.instant(),
                List.of(new EarnLine(UUID.randomUUID(), UUID.randomUUID(), CustomersFixtures.mzn(gross), false)));
    }

    /** Redeems {@code rewardCode} on a new line of a unit at {@code unitPrice}, in its own transaction. */
    public RedemptionResult redeem(UUID customer, String rewardCode, String unitPrice) {
        return tx.execute(status -> loyalty.redeem(redeemCommand(customer, rewardCode, UUID.randomUUID(),
                UUID.randomUUID(), null, unitPrice)));
    }

    public static RedeemCommand redeemCommand(UUID customer, String rewardCode, UUID line, UUID item, UUID family,
                                              String unitPrice) {
        return new RedeemCommand(customer, rewardCode, line, UUID.randomUUID(), STORE, DAY, item, family,
                CustomersFixtures.mzn(unitPrice));
    }

    public long balance(UUID customer) {
        return loyalty.balance(customer).points();
    }

    public static Money mzn(String amount) {
        return CustomersFixtures.mzn(amount);
    }

    public ResultActions createDraft(String spendUnit, int pointsPerUnit, String itemIds, String familyIds)
            throws Exception {
        return mvc.perform(idem(post(PROGRAMS)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"spendUnit": %s, "pointsPerUnit": %d, "itemIds": [%s], "familyIds": [%s]}"""
                        .formatted(spendUnit, pointsPerUnit, itemIds, familyIds)));
    }

    public UUID draft(String spendUnit, int pointsPerUnit) throws Exception {
        return id(createDraft(spendUnit, pointsPerUnit, "", "").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    public ResultActions addReward(UUID versionId, String body) throws Exception {
        return mvc.perform(idem(post(PROGRAMS + "/" + versionId + "/rewards")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public static String reward(String code, int cost, String effect, String amount, UUID itemId, UUID familyId) {
        return """
                {"code": "%s", "name": "Reward %s", "pointsCost": %d, "effect": "%s", "amount": %s,
                 "itemId": %s, "familyId": %s}""".formatted(code, code, cost, effect, amount,
                quoted(itemId), quoted(familyId));
    }

    public ResultActions publish(UUID versionId, long version) throws Exception {
        return mvc.perform(idem(post(PROGRAMS + "/" + versionId + "/publish")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"version": %d}""".formatted(version)));
    }

    /** Publishes a new version (spend unit, points per unit, no scope) with {@code rewards}; returns its id. */
    public UUID publishProgram(String spendUnit, int pointsPerUnit, String... rewards) throws Exception {
        UUID draft = draft(spendUnit, pointsPerUnit);
        for (String reward : rewards) {
            addReward(draft, reward).andExpect(status().isCreated());
        }
        publish(draft, 0).andExpect(status().isOk());
        return draft;
    }

    /** A new customer at a new store's POS. */
    public UUID customer() throws Exception {
        return customers.newCustomer(cashier(), "Member " + CustomersFixtures.token(), null, null);
    }

    /** A new customer, enrolled at a POS. */
    public UUID member() throws Exception {
        UUID customer = customer();
        enroll(cashier(), customer).andExpect(status().isCreated());
        return customer;
    }

    public ResultActions enroll(Pos pos, UUID customer) throws Exception {
        return mvc.perform(idem(post(CustomersFixtures.POS + "/" + customer + "/loyalty"))
                .with(pos.with(Permission.LOYALTY_ENROLL)));
    }

    public Pos cashier() throws Exception {
        return CustomersFixtures.pos(customers.newStore());
    }

    public static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return account(null, Permission.LOYALTY_PROGRAM_MANAGE);
    }

    public static long version(ResultActions result) throws Exception {
        return json(result.andReturn().getResponse().getContentAsString()).get("version").asLong();
    }

    static String quoted(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
