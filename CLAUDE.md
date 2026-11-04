# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

The product specification is [docs/product_description.md](docs/product_description.md). It is written in Portuguese and is authoritative: one installation per company (no multi-tenancy), a Spring Modulith modular monolith, PostgreSQL with Flyway, and React frontends for the POS and BackOffice. Read it before designing or implementing features.

restaurantPRO is at the scaffold stage. Only `backend/` exists: a freshly generated Spring Boot app with a single `BackendApplication` class and a context-load test. There is no domain code, persistence layer, or frontend in this directory yet.

## Shell commands: always through `rtk`

Every Bash command in this project runs through [RTK](https://github.com/rtk-ai/rtk), which filters command output to save tokens. The global rewrite hook (`rtk init -g`) is installed on this machine, but still write the `rtk` prefix explicitly in every command, including the commands inside subagents. The prefix keeps commands correct on machines without the hook, and the hook leaves commands that already start with `rtk` unchanged.

| Instead of | Use |
| --- | --- |
| `./mvnw <goals>` | `rtk mvn <goals>` (it detects and runs `./mvnw` itself) |
| `git <cmd>` | `rtk git <cmd>` |
| `pnpm install` / `pnpm typecheck` | `rtk pnpm install` / `rtk pnpm typecheck` |
| `pnpm test`, `pnpm --filter <app> test` | `rtk test pnpm test`, `rtk test pnpm --filter <app> test` |
| `pnpm lint` | `rtk lint` |
| Playwright (`pnpm exec playwright test`) | `rtk playwright test` |
| `docker`, `psql`, `gh`, `grep`, `find`, `ls`, `curl` | `rtk docker`, `rtk psql`, `rtk gh`, `rtk grep`, `rtk find`, `rtk ls`, `rtk curl` |
| Anything without its own filter | `rtk err <cmd>` (errors only) or `rtk summary <cmd>` |
| Long-running processes (dev servers) or when you need the full output | `rtk proxy <cmd>` (no filter) |

To check the equivalent of a command, run `rtk rewrite "<cmd>"`. If a filter hides information you need for debugging, run the command again with `rtk proxy`.

## Backend (`backend/`)

- Spring Boot 4.1.2 (parent POM), Java 25, Maven wrapper (Maven 3.10.0).
- Dependencies: `spring-boot-starter-webmvc` (servlet stack, not WebFlux), `spring-boot-devtools`, `spring-boot-starter-webmvc-test`.
- Base package: `com.xavier.backend`. Config: `src/main/resources/application.yaml`.

Run these from `backend/`:

```bash
rtk proxy ./mvnw spring-boot:run                            # run the app (port 8080 by default)
rtk mvn test                                                # run all tests
rtk mvn test -Dtest=BackendApplicationTests                 # run one test class
rtk mvn test -Dtest=BackendApplicationTests#contextLoads    # run one test method
rtk mvn package                                             # build the jar into target/
```

The empty `<license>`, `<developers>` and `<scm>` elements in `pom.xml` are deliberate. They block inheritance from the Spring Boot parent POM (see `HELP.md`), so leave them in place.

## Git

The enclosing git repository is rooted at `/Users/xavier` (the home directory), not at this project. `git status` therefore lists unrelated home-directory files. Scope git commands to this directory (for example `rtk git status .` or `rtk git add <paths>`), and never run `git add -A` or `git add .` from a parent directory.

`.github/modernize/java-upgrade/` holds tool-use recording hooks from a Java-upgrade tool. It is not CI.
