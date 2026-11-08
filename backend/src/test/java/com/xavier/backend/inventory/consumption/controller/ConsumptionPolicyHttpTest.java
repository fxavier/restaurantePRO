package com.xavier.backend.inventory.consumption.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.InventoryFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.organization.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D116: the consumption policy is versioned like recipes. Version 1 (seeded) tracks every family. Every version a
 * test activates keeps the default rule tracked, because the policy is company-wide and the database is shared.
 */
@InventoryModuleTest
class ConsumptionPolicyHttpTest {

    static final String POLICIES = BO + "/consumption-policies";

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Test
    void version1TracksEveryFamily() throws Exception {
        JsonNode first = json(mvc.perform(get(POLICIES).with(manager())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);

        assertThat(first.get("versionNo").asInt()).isEqualTo(1);
        assertThat(first.get("rules").get(0).get("familyId").isNull()).isTrue();
        assertThat(first.get("rules").get(0).get("tracked").asBoolean()).isTrue();
        assertThat(first.get("rules").get(0).get("trigger").asString()).isEqualTo("ROUND_CONFIRMED");
    }

    @Test
    void aDraftIsEditedThenActivatedAndSupersedesTheCurrentVersion() throws Exception {
        UUID family = UUID.randomUUID();
        int versionNo = json(draft(rules(family)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString())
                .get("versionNo").asInt();
        mvc.perform(idem(put(POLICIES + "/" + versionNo)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\": [{\"tracked\": true}, {\"familyId\": \"%s\", \"tracked\": false}],"
                                .formatted(family) + " \"version\": 0}"))
                .andExpect(status().isOk());
        Instant from = afterTheVersionInForce();

        activate(versionNo, from).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveFrom").value(from.toString()));

        JsonNode all = json(mvc.perform(get(POLICIES).with(manager())).andReturn().getResponse()
                .getContentAsString());
        JsonNode previous = all.get(all.size() - 2);
        assertThat(previous.get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(previous.get("effectiveTo").asString()).isEqualTo(from.toString());
        mvc.perform(idem(put(POLICIES + "/" + versionNo)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\": [{\"tracked\": true}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECIPE_VERSION_IMMUTABLE"));
    }

    @Test
    void twoRulesForOneFamilyAreRefused() throws Exception {
        UUID family = UUID.randomUUID();
        draft("[{\"tracked\": true}, {\"familyId\": \"%s\", \"tracked\": false}, {\"familyId\": \"%s\", \"tracked\": true}]"
                .formatted(family, family))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POLICY_RULE_DUPLICATE"));
    }

    @Test
    void policiesNeedRecipeManage() throws Exception {
        mvc.perform(get(POLICIES).with(account(null, Permission.INVENTORY_VIEW))).andExpect(status().isForbidden());
    }

    @Test
    void policyWritesNeedAnInstallationWideGrantButReadsAcceptAStoreGrant() throws Exception {
        org.springframework.test.web.servlet.request.RequestPostProcessor storeManager =
                account(UUID.randomUUID(), Permission.RECIPE_MANAGE);
        int versionNo = json(draft(rules(UUID.randomUUID())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("versionNo").asInt();

        mvc.perform(get(POLICIES).with(storeManager)).andExpect(status().isOk());
        mvc.perform(idem(post(POLICIES)).with(storeManager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\": [{\"tracked\": true}]}")).andExpect(status().isForbidden());
        mvc.perform(idem(put(POLICIES + "/" + versionNo)).with(storeManager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\": [{\"tracked\": true}]}")).andExpect(status().isForbidden());
        mvc.perform(idem(post(POLICIES + "/" + versionNo + "/activate")).with(storeManager)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    /** An instant after now and after the start of the version in force (other tests activate versions too). */
    private Instant afterTheVersionInForce() throws Exception {
        Instant activeFrom = Instant.EPOCH;
        for (JsonNode version : json(mvc.perform(get(POLICIES).with(manager())).andReturn().getResponse()
                .getContentAsString())) {
            if ("ACTIVE".equals(version.get("status").asString())) {
                activeFrom = Instant.parse(version.get("effectiveFrom").asString());
            }
        }
        return (activeFrom.isAfter(clock.instant()) ? activeFrom : clock.instant()).plus(Duration.ofDays(1));
    }

    private ResultActions draft(String rules) throws Exception {
        return mvc.perform(idem(post(POLICIES)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\": " + rules + "}"));
    }

    private ResultActions activate(int versionNo, Instant from) throws Exception {
        return mvc.perform(idem(post(POLICIES + "/" + versionNo + "/activate")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\": \"%s\"}".formatted(from)));
    }

    private static String rules(UUID untracked) {
        return "[{\"tracked\": true}, {\"familyId\": \"%s\", \"tracked\": false}]".formatted(untracked);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return account(null, Permission.RECIPE_MANAGE);
    }
}
