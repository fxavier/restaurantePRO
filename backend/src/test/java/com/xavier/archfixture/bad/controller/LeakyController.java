package com.xavier.archfixture.bad.controller;

import com.xavier.archfixture.bad.model.BadEntity;
import com.xavier.archfixture.bad.repository.BadRepository;
import org.springframework.web.bind.annotation.RestController;

/** Fixture: a controller that reads a repository and returns an entity. */
@RestController
public class LeakyController {

    private final BadRepository repository = new BadRepository();

    public BadEntity get() {
        return repository.find();
    }
}
