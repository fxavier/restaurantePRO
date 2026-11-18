# 04: Frontend workspace scaffold

**Status:** ready-for-agent
**Blocked by:** 01
**Owner:** `frontend-developer`, with `software-architect` for the structure. Spec: FC §1–§2, §7–§9, §15; DS; ADR 0042, 0043.

## What to build

The pnpm workspace with both apps booting to a placeholder screen in Portuguese. The UI package holds the design tokens, Inter, and the base shadcn components of the DS inventory, plus the shared primitives `ConnectionBanner`, `DataState`, `ConfirmButton`, `StatusBadge` and `Money`. The API-client package holds the committed snapshot from 01, the generated types, the client factory with per-app middleware, the one `ApiError` mapping (network failures included) and the `fetch`-based SSE reader. The workspace scripts are lint, typecheck, test, build and generate. A GitHub Actions workflow runs lint, typecheck, Vitest, build and the snapshot drift check. One Playwright smoke test per app runs against the built app.

## Acceptance criteria

- [ ] `rtk pnpm install`, `rtk lint`, `rtk pnpm typecheck`, `rtk test pnpm test`, `rtk pnpm build` and `rtk playwright test` pass from `frontend/`.
- [ ] The tokens match the DS table. A test asserts the primary/white and muted-text contrast pairs.
- [ ] Unit tests prove the `ApiError` mapping (problem+json, validation `errors[]`, step-up fields, network failure) and the SSE parser (events, comments, split chunks, reconnect signal), each red first.
- [ ] POS middleware adds `Authorization` and `X-Pos-Session`. BackOffice middleware adds `X-XSRF-TOKEN` on unsafe methods and sends credentials. Both are proven by tests.
- [ ] Only the dependencies of FC §1 are added. The pnpm version is pinned.
- [ ] The workflow runs on PRs that touch the frontend or the snapshot.
