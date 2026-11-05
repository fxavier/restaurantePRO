package com.xavier.backend.customers.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;

/**
 * Where a customers use case is called from, which decides how it is authorized: the POS needs an employee session
 * on a terminal of an active store ({@code requirePos}); the BackOffice needs the permission installation-wide or in
 * any store, because customers are company-wide (P38, D92).
 */
public enum Channel {
    POS {
        @Override
        void authorize(AccessPolicy accessPolicy, Permission permission) {
            accessPolicy.requirePos(permission);
        }
    },
    BACKOFFICE {
        @Override
        void authorize(AccessPolicy accessPolicy, Permission permission) {
            accessPolicy.requireInAnyStore(permission);
        }
    };

    abstract void authorize(AccessPolicy accessPolicy, Permission permission);
}
