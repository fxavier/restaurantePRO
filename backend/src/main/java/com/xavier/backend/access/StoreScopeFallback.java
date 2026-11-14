package com.xavier.backend.access;

/**
 * The store scope of a thread with neither an explicit scope nor an authenticated actor (D164 "Nothing"). Production
 * answers {@link StoreScope#UNSET}, so a protected table raises {@code RS001}; the test harness answers all stores on
 * the JUnit thread only, for fixtures (D171).
 */
public interface StoreScopeFallback {

    StoreScope whenNoActor();
}
