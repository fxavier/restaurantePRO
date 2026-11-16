package com.xavier.archfixture.tenant.orders.controller;

import com.xavier.archfixture.tenant.orders.model.dto.TransferRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fixture: four ways of taking a tenant from a request (security rule 1). */
@RestController
public class TenantPathController {

    @GetMapping("/orders/{tenantId}")
    public String byPath(@PathVariable("tenantId") UUID tenant) {
        return tenant.toString();
    }

    @GetMapping("/orders")
    public String byParameter(@RequestParam UUID tenantId) {
        return tenantId.toString();
    }

    @GetMapping("/orders/mine")
    public String byHeader(@RequestHeader("X-Tenant-Id") String selected) {
        return selected;
    }

    @PostMapping("/orders/transfers")
    public String byBody(@RequestBody TransferRequest request) {
        return request.toString();
    }
}
