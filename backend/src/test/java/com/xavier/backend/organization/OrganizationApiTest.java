package com.xavier.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.organization.exception.StoreNotFoundException;
import com.xavier.backend.organization.model.dto.StoreCreateRequest;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.service.StoreService;
import com.xavier.backend.organization.service.TerminalService;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/** Other modules call OrganizationApi after their own authorization, or before login: it needs no actor. */
@OrganizationModuleTest
@WithActor(permissions = {Permission.STORE_MANAGE, Permission.TERMINAL_MANAGE})
class OrganizationApiTest {

    @Autowired
    OrganizationApi organization;

    @Autowired
    StoreService stores;

    @Autowired
    TerminalService terminals;

    @Test
    void findsAStoreWithTheCompanyCurrency() {
        StoreSummary created = newStore("Europe/Lisbon");
        SecurityContextHolder.clearContext();

        assertThat(organization.findStore(created.id())).hasValueSatisfying(store -> {
            assertThat(store.code()).isEqualTo(created.code());
            assertThat(store.businessDayCutoff()).isEqualTo(LocalTime.of(5, 0));
            assertThat(store.currency()).isEqualTo(Currency.getInstance("MZN"));
        });
        assertThat(organization.storeExists(created.id())).isTrue();
        assertThat(organization.zoneOf(created.id())).isEqualTo(ZoneId.of("Europe/Lisbon"));
    }

    @Test
    void anUnknownStoreIsAbsent() {
        UUID unknown = UUID.randomUUID();

        assertThat(organization.findStore(unknown)).isEmpty();
        assertThat(organization.storeExists(unknown)).isFalse();
        assertThatThrownBy(() -> organization.zoneOf(unknown)).isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void findsOnlyActiveTerminalsByTheirDeviceToken() {
        StoreSummary store = newStore("Africa/Maputo");
        TerminalSummary terminal = terminals.create(new TerminalCreateRequest(store.id(), "POS1", "Bar"));
        String token = terminals.enroll(terminals.issueEnrollmentCode(terminal.id()).code()).deviceToken();

        assertThat(organization.findTerminalByDeviceToken(token)).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(terminal.id());
            assertThat(found.storeId()).isEqualTo(store.id());
            assertThat(found.status()).isEqualTo("ACTIVE");
        });
        assertThat(organization.findTerminalByDeviceToken("not-a-token")).isEmpty();
        assertThat(organization.findTerminalByDeviceToken("")).isEmpty();
        assertThat(organization.findTerminalByDeviceToken(null)).isEmpty();

        terminals.revoke(terminal.id());
        assertThat(organization.findTerminalByDeviceToken(token)).isEmpty();
    }

    @Test
    void exposesTheCompanyProfile() {
        SecurityContextHolder.clearContext();

        assertThat(organization.companyProfile().currency()).isEqualTo(Currency.getInstance("MZN"));
        assertThat(organization.companyProfile().country()).isEqualTo("MZ");
    }

    private StoreSummary newStore(String timezone) {
        return stores.create(new StoreCreateRequest("A" + UUID.randomUUID().toString().substring(0, 8), "Store",
                timezone, null));
    }
}
