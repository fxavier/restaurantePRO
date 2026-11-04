package com.xavier.archfixture.bad.model.dto;

import com.xavier.archfixture.bad.model.BadEntity;

/** Fixture: a DTO that carries an entity. */
public record BadResponse(BadEntity entity) {
}
