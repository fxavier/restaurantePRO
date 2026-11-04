package com.xavier.archfixture.bad.repository;

import com.xavier.archfixture.bad.model.BadEntity;

/** Fixture: a repository used directly by a controller. */
public class BadRepository {

    public BadEntity find() {
        return new BadEntity();
    }
}
