/**
 * Events orders publishes (the contract for production, S15). Records with ids and the snapshot values consumers
 * print; published inside the use case's transaction, so the Modulith publication registry persists them with it.
 */
@NamedInterface("event")
package com.xavier.backend.orders.model.event;

import org.springframework.modulith.NamedInterface;
