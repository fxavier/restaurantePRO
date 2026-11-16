package com.xavier.archfixture.tenant.orders.controller;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Fixture: a store id from the path is not a tenant. */
@RestController
public class CleanController {

    @GetMapping("/stores/{storeId}")
    public String byStore(@PathVariable UUID storeId) {
        return storeId.toString();
    }
}
