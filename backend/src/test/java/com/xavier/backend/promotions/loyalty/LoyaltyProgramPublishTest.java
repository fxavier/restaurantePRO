package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.id;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.json;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.PROGRAMS;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.manager;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D111: company-wide program versions DRAFT → PUBLISHED → RETIRED. Publishing a version retires the one in force (one
 * PUBLISHED at a time); only a DRAFT is edited, gets rewards or is published (409 {@code LOYALTY_PROGRAM_NOT_DRAFT}),
 * and the database refuses any change to a published version or its children. {@code LOYALTY_PROGRAM_MANAGE}.
 */
@LoyaltyModuleTest
class LoyaltyProgramPublishTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aNewVersionIsADraftWithTheNextNumberInTheCompanyCurrency() throws Exception {
        UUID item = UUID.randomUUID();
        String first = loyalty.createDraft("10.00", 2, "\"" + item + "\"", "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.spendUnit.amount").value(10.00))
                .andExpect(jsonPath("$.spendUnit.currency").value("MZN"))
                .andExpect(jsonPath("$.pointsPerUnit").value(2))
                .andExpect(jsonPath("$.itemIds[0]").value(item.toString()))
                .andReturn().getResponse().getContentAsString();
        String second = loyalty.createDraft("5.00", 1, "", "").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(json(second).get("versionNo").asInt()).isEqualTo(json(first).get("versionNo").asInt() + 1);
    }

    @Test
    void publishingRetiresTheVersionInForce() throws Exception {
        UUID first = loyalty.publishProgram("10.00", 1);
        UUID second = loyalty.draft("20.00", 3);

        loyalty.publish(second, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        mvc.perform(get(PROGRAMS + "/" + first).with(manager())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").isNotEmpty());
        assertThat(jdbc.sql("SELECT id FROM promotions.loyalty_program_version WHERE status = 'PUBLISHED'")
                .query(UUID.class).list()).containsExactly(second);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = 'LOYALTY_PROGRAM_PUBLISHED' AND entity_id = ?""")
                .param(second.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void onlyADraftIsPublishedEditedOrGivenRewards() throws Exception {
        UUID published = loyalty.publishProgram("10.00", 1);

        loyalty.publish(published, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_NOT_DRAFT"));
        mvc.perform(idem(put(PROGRAMS + "/" + published)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "spendUnit": 5.00, "pointsPerUnit": 1, "itemIds": [], "familyIds": []}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_NOT_DRAFT"));
        loyalty.addReward(published, reward("LATE", 10, "FREE_UNIT", "null", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_NOT_DRAFT"));
    }

    @Test
    void aDraftIsEditedAtItsVersionAndAStaleEditIsAConflict() throws Exception {
        UUID draft = loyalty.draft("10.00", 1);
        UUID family = UUID.randomUUID();
        String edit = """
                {"version": 0, "spendUnit": 25.00, "pointsPerUnit": 4, "itemIds": [], "familyIds": ["%s"]}"""
                .formatted(family);

        mvc.perform(idem(put(PROGRAMS + "/" + draft)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendUnit.amount").value(25.00))
                .andExpect(jsonPath("$.pointsPerUnit").value(4))
                .andExpect(jsonPath("$.familyIds[0]").value(family.toString()))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(idem(put(PROGRAMS + "/" + draft)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content(edit))
                .andExpect(status().isConflict());
    }

    @Test
    void rewardsAreValidatedAndTheirCodeIsUniquePerVersion() throws Exception {
        UUID draft = loyalty.draft("10.00", 1);

        String created = loyalty.addReward(draft, reward("COFFEE", 50, "FREE_UNIT", "null", UUID.randomUUID(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COFFEE"))
                .andExpect(jsonPath("$.effect").value("FREE_UNIT"))
                .andReturn().getResponse().getContentAsString();
        loyalty.addReward(draft, reward("COFFEE", 60, "FREE_UNIT", "null", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_REWARD_CODE_TAKEN"));
        loyalty.addReward(draft, reward("OFF", 60, "AMOUNT_OFF_UNIT", "null", null, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_INVALID"));
        loyalty.addReward(draft, reward("OFF", 60, "AMOUNT_OFF_UNIT", "1.001", null, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_INVALID"));
        loyalty.addReward(draft, reward("BOTH", 60, "FREE_UNIT", "null", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_PROGRAM_INVALID"));

        mvc.perform(delete(PROGRAMS + "/" + draft + "/rewards/" + id(created)).with(manager())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(PROGRAMS + "/" + draft).with(manager())).andExpect(status().isOk())
                .andExpect(jsonPath("$.rewards").isEmpty());
    }

    @Test
    void thePublishedVersionAndItsChildrenAreFrozenInTheDatabase() throws Exception {
        UUID draft = loyalty.draft("10.00", 1);
        loyalty.addReward(draft, reward("TEA", 30, "FREE_UNIT", "null", null, null)).andExpect(status().isCreated());
        jdbc.sql("INSERT INTO promotions.loyalty_program_scope (version_id, item_id) VALUES (?, ?)")
                .params(draft, UUID.randomUUID()).update();
        loyalty.publish(draft, 0).andExpect(status().isOk());

        assertRefused("UPDATE promotions.loyalty_program_version SET points_per_unit = 9 WHERE id = ?", draft);
        assertRefused("DELETE FROM promotions.loyalty_program_version WHERE id = ?", draft);
        assertRefused("UPDATE promotions.loyalty_reward SET points_cost = 1 WHERE version_id = ?", draft);
        assertRefused("DELETE FROM promotions.loyalty_program_scope WHERE version_id = ?", draft);
        assertRefused("""
                INSERT INTO promotions.loyalty_reward (id, version_id, code, name, points_cost, effect)
                VALUES (gen_random_uuid(), ?, 'NEW', 'New', 1, 'FREE_UNIT')""", draft);
    }

    @Test
    void managingTheProgramNeedsTheProgramPermission() throws Exception {
        mvc.perform(idem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PROGRAMS))
                        .with(account(null, Permission.LOYALTY_VIEW, Permission.LOYALTY_ADJUST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spendUnit": 10.00, "pointsPerUnit": 1, "itemIds": [], "familyIds": []}"""))
                .andExpect(status().isForbidden());
    }

    private void assertRefused(String sql, Object param) {
        assertThatThrownBy(() -> jdbc.sql(sql).param(param).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("not allowed");
    }
}
