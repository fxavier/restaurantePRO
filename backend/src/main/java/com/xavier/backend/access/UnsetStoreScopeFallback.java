package com.xavier.backend.access;

import org.springframework.stereotype.Component;

/** Production: a thread with no actor and no explicit scope has no scope (fail closed, P63). */
@Component
class UnsetStoreScopeFallback implements StoreScopeFallback {

    @Override
    public StoreScope whenNoActor() {
        return StoreScope.UNSET;
    }
}
