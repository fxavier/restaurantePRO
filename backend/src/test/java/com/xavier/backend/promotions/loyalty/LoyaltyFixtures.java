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
import java.util.UUID;
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

    private final MockMvc mvc;
    private final CustomersFixtures customers;

    LoyaltyFixtures(MockMvc mvc, CustomersFixtures customers) {
        this.mvc = mvc;
        this.customers = customers;
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
