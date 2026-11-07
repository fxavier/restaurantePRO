package com.xavier.backend.promotions.loyalty.model;

import com.xavier.backend.shared.Money;

/** What an order earns under a program version: the eligible gross and the whole points it gives (P45). */
public record Earning(Money eligibleGross, int points) {
}
