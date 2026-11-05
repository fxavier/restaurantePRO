package com.xavier.backend.production.model;

/** Where a network printer listens (ESC/POS raw TCP, usually port 9100). */
public record PrinterAddress(String host, int port) {
}
