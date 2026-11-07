package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.InventoryFixtures.json;
import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * D116: a family the policy in force at the confirmation does not track consumes nothing and raises no exception;
 * the default rule tracks the rest. The version activated here keeps the default tracked (the policy is
 * company-wide and the database is shared).
 */
@InventoryModuleTest
class ConsumptionPolicyTrackingTest extends ConsumptionTestBase {

    @Test
    void anUntrackedFamilyConsumesNothingFromThePolicyVersionInForce() throws Exception {
        UUID drinks = UUID.randomUUID();
        UUID water = kitchen.catalogItem(drinks);
        UUID soup = kitchen.catalogItem(null);
        UUID bottle = kitchen.stockItem("un");
        UUID stock = kitchen.stockItem("ml");
        kitchen.saleRecipe(water, null, "1", ingredient(bottle, "1", "un"));
        kitchen.saleRecipe(soup, null, "1", ingredient(stock, "300", "ml"));
        Instant from = activateUntracking(drinks);
        BatchFacts before = kitchen.round(from.minusSeconds(1), OrderMode.DINE_IN, line(water, "1", "KITCHEN"));
        BatchFacts after = kitchen.round(from, OrderMode.DINE_IN, line(water, "1", "KITCHEN"),
                line(soup, "1", "KITCHEN"));

        consumption.consume(before.batchId());
        consumption.consume(after.batchId());

        assertThat(kitchen.movements("ORDER_LINE", before.lines().getFirst().lineId())).hasSize(1);
        assertThat(kitchen.movements("ORDER_LINE", after.lines().get(0).lineId())).isEmpty();
        assertThat(kitchen.movements("ORDER_LINE", after.lines().get(1).lineId())).hasSize(1);
        assertThat(kitchen.exceptions()).isEmpty();
    }

    /** A new policy version, after the one in force, that stops tracking {@code family}; when it takes effect. */
    private Instant activateUntracking(UUID family) throws Exception {
        String policies = BO + "/consumption-policies";
        JsonNode all = json(mvc.perform(get(policies).with(manager())).andReturn().getResponse()
                .getContentAsString());
        Instant activeFrom = Instant.EPOCH;
        for (JsonNode version : all) {
            if ("ACTIVE".equals(version.get("status").asString())) {
                activeFrom = Instant.parse(version.get("effectiveFrom").asString());
            }
        }
        Instant from = (activeFrom.isAfter(clock.instant()) ? activeFrom : clock.instant()).plus(Duration.ofHours(1));
        int versionNo = json(mvc.perform(idem(post(policies)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\": [{\"tracked\": true}, {\"familyId\": \"%s\", \"tracked\": false}]}"
                                .formatted(family)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("versionNo").asInt();
        mvc.perform(idem(post(policies + "/" + versionNo + "/activate")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\": \"%s\"}".formatted(from)))
                .andExpect(status().isOk());
        return from;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return account(null, Permission.RECIPE_MANAGE);
    }
}
