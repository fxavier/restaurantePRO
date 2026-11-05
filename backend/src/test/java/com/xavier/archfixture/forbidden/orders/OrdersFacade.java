package com.xavier.archfixture.forbidden.orders;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Test fixture only: a module facade with read-only and writing methods (D65 reporting rule). */
@Service
public class OrdersFacade {

    @Transactional(readOnly = true)
    public String find() {
        return "read";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close() {
        // a write
    }

    /** No @Transactional at all: not read-only either. */
    public void reopen() {
        // a write
    }

    /** Read-only through its class. */
    @Service
    @Transactional(readOnly = true)
    public static class CountingFacade {
        public long count() {
            return 0;
        }
    }
}
