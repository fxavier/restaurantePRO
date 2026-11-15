# Issue tracker: Local Markdown

Issues for this repo live as Markdown files under `docs/issues/`. Specs (PRDs) live under `docs/prd/`. External PRs are not a triage surface.

## Conventions

- One feature or run per directory: `docs/issues/<feature-slug>/` (for example `docs/issues/r6/`).
- The spec is `docs/prd/<feature-slug>.md` (for example `docs/prd/r6-multitenancy.md`), not a file inside the issue directory.
- One file per issue, named by its ticket id and slug: `docs/issues/<feature-slug>/<ID>-<slug>.md` (for example `S71-prefactor.md`).
- Triage state is a `Status:` line near the top of each issue file (role strings in `triage-labels.md`).
- Comments and conversation history append to the bottom of the file under a `## Comments` heading.

## When a skill says "publish to the issue tracker"

Create a new file under `docs/issues/<feature-slug>/`, creating the directory if needed.

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user normally passes the path or the ticket id directly.
