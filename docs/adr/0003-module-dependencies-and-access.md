# 0003. Allowed module dependencies, with authorization in `access`

Status: Accepted, 2026-11-04 (D13, including the user's amendment of 2026-11-04). Source: [backend-decisions.md](../design/backend-decisions.md) D13; spec §6. R2 amendments: [ADR 0011](0011-r2-module-dependencies.md).

## Context

Spec §6 fixes directions such as `orders ↛ settlement` and `cash ↛ orders`. A rule that is not checked drifts. The first D13 table put `AccessPolicy` in `identity`, and `organization` needed it, while `identity` needed `organization` for stores and terminals: a cycle.

## Decision

Each module declares `@ApplicationModule(allowedDependencies = {...})` in its `package-info.java`, following the D13 table. `shared` is an OPEN module with technical types only and depends on nothing. **Amendment:** the authorization primitives `Permission`, `Actor` and `AccessPolicy` live in their own module, `access`, which depends only on `shared`. Every business module may depend on `access`. `identity` builds the `Actor` at login and depends on no business module other than `organization`, so later modules may call its root API (`ApproverVerifier`) without a cycle. Public API is the root package plus `@NamedInterface` packages (`model/dto`, `model/event`). Entities, repositories, services and controllers stay internal.

## Consequences

- `ModularityTests.modulesRespectTheirDeclaredBoundaries` runs `ApplicationModules.verify()`; `anIllegalModuleDependencyIsDetected` proves an injected `orders → settlement` or `cash → orders` fails it.
- Modulith rejects an unknown module name, so a module names only modules that already exist (D1).
- `config` exists as a module of its own (security, sessions, scheduling, the SSE controller). It depends on `shared` and `access`, and nothing depends on it.
