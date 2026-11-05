package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.PIN;
import static com.xavier.backend.orders.OrdersFixtures.POS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * r2-decisions §4 orders: a void never exceeds the remaining net quantity, checked under the order's incremented
 * version. Two terminals voiding 2 each of a line of 3 at the same order version: one void, one 409, net 1.
 */
@OrdersModuleTest
class ConcurrentVoidTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();

    @RepeatedTest(3)
    void twoVoidsOfOneLineAtOnceNeverTakeMoreThanItHolds() throws Exception {
        Shop shop = fixtures.shop();
        UUID terminal = UUID.randomUUID();
        UUID order = fixtures.openedAt(shop, shop.table(), employee, terminal);
        UUID line = fixtures.confirmedLine(shop, order, "3", 0, employee, terminal);
        Employee manager = fixtures.employee(shop, IdentityFixtures.MANAGER);

        List<Integer> statuses = OrdersFixtures.race(
                () -> voidTwo(shop, order, line, manager),
                () -> voidTwo(shop, order, line, manager));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(fixtures.netQuantity(order)).isEqualByComparingTo("1");
    }

    int voidTwo(Shop shop, UUID order, UUID line, Employee approver) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/voids")
                        .with(OrdersActors.pos(shop.store(), employee, UUID.randomUUID(), Permission.ORDER_VOID))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", approver.code()).header("X-Approver-Pin", PIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": 1, "lineId": "%s", "quantity": 2, "reason": "spilled", "wasted": true}"""
                                .formatted(line)))
                .andReturn().getResponse().getStatus();
    }
}
