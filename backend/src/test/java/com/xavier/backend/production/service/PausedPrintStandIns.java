package com.xavier.backend.production.service;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * For the suites that are the print bridge themselves (they claim over HTTP): during each test no
 * {@link InProcessPrintBridge} of any cached context claims, so the jobs a test queues are claimed by the test alone.
 */
public class PausedPrintStandIns implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
            PausedPrintStandIns.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put("pause", InProcessPrintBridge.pauseEverywhere());
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        AutoCloseable pause = context.getStore(NAMESPACE).remove("pause", AutoCloseable.class);
        if (pause != null) {
            pause.close();
        }
    }
}
