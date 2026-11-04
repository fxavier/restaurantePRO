package com.xavier.archfixture.bad.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.beans.factory.annotation.Autowired;

/** Fixture: money as double, field injection. */
@Entity
public class BadEntity {

    @Id
    private Long id;

    private double amount;

    @Autowired
    private Object injected;

    public double total() {
        return amount;
    }
}
