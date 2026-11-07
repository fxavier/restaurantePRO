package com.xavier.backend.inventory.recipe.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.recipe.model.dto.ActivateRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeUpdateRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionView;
import com.xavier.backend.inventory.recipe.model.dto.RecipeView;
import com.xavier.backend.inventory.recipe.service.RecipeService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Recipes and their versions (D115, PRD R4 S47), {@code RECIPE_MANAGE}; every write takes an Idempotency-Key. */
@RestController
class RecipeController {

    static final String PATH = "/api/v1/backoffice/inventory/recipes";

    private final RecipeService recipes;
    private final IdempotentRequests idempotent;

    RecipeController(RecipeService recipes, IdempotentRequests idempotent) {
        this.recipes = recipes;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<RecipeView> list(Pageable pageable) {
        return recipes.list(pageable);
    }

    @GetMapping(PATH + "/{id}")
    RecipeView get(@PathVariable UUID id) {
        return recipes.get(id);
    }

    @PostMapping(PATH)
    ResponseEntity<RecipeView> create(@RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody RecipeRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.recipe.create", key, request, body, RecipeView.class, () -> {
            RecipeView created = recipes.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PutMapping(PATH + "/{id}")
    ResponseEntity<RecipeView> rename(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody RecipeUpdateRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.recipe.rename", key, request, body, RecipeView.class,
                () -> ResponseEntity.ok(recipes.rename(id, body)));
    }

    @PostMapping(PATH + "/{id}/versions")
    ResponseEntity<RecipeVersionView> addVersion(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody RecipeVersionRequest body,
                                                 HttpServletRequest request) {
        return idempotent.execute("inventory.recipe.version.create", key, request, body, RecipeVersionView.class,
                () -> {
                    RecipeVersionView created = recipes.addVersion(id, body);
                    return ResponseEntity.created(URI.create(PATH + "/" + id + "/versions/" + created.versionNo()))
                            .body(created);
                });
    }

    @PutMapping(PATH + "/{id}/versions/{versionNo}")
    ResponseEntity<RecipeVersionView> revise(@PathVariable UUID id, @PathVariable int versionNo,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody RecipeVersionRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.recipe.version.revise", key, request, body, RecipeVersionView.class,
                () -> ResponseEntity.ok(recipes.reviseVersion(id, versionNo, body)));
    }

    @PostMapping(PATH + "/{id}/versions/{versionNo}/activate")
    ResponseEntity<RecipeVersionView> activate(@PathVariable UUID id, @PathVariable int versionNo,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @RequestBody(required = false) ActivateRequest body,
                                               HttpServletRequest request) {
        ActivateRequest activation = body == null ? new ActivateRequest(null) : body;
        return idempotent.execute("inventory.recipe.version.activate", key, request, activation,
                RecipeVersionView.class, () -> ResponseEntity.ok(recipes.activate(id, versionNo, activation)));
    }
}
