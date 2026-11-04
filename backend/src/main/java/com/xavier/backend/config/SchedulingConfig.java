package com.xavier.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * D46: Spring scheduling for the single backend instance of an installation (idempotency retention, notification
 * heartbeat, later sweepers and print workers). Jobs stay correct under overlap through {@code SKIP LOCKED} and
 * compare-and-set updates, so no distributed lock is needed.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
