# Parallel dev slots

Parallel dev slots let host-based agents work from separate Git worktrees while sharing the project checkout's ignored setup inputs.

The main checkout owns `.dev-state` and the shared `data.dev/filestore`.

Treat `.dev-state` as sensitive because Datomic templates and hydrated slot databases may contain production-derived data.

Do not stage `.dev-state`, generated slot files, or ignored artifact links unless an operator explicitly asks for that.

This file is the canonical operator reference for the dev-slot workflow.

Agent skills should link here instead of duplicating the full workflow.

## Prepare ignored artifacts

Import WebAwesome vendor files, the local WebAwesome skill, the Datastar inspector, generated Java classes, and ignored Phosphor icon files from a checkout that already has them.

```bash
bb dev-slot artifacts import --source-root /home/ramblurr/src/sno/probematic
```

Link cached artifacts into the claimed slot worktree after the worktree exists.

```bash
bb dev-slot artifacts link agent-1
```

The application permits these external classpath symlinks only under the development profile.

## Prepare a Datomic template

Create a template from the active dev Datomic SQLite store when the active store is already suitable.

```bash
bb dev-slot template from-active --name dev-current
```

Create a template from an existing Datomic data directory when a restored data directory already exists locally.

```bash
bb dev-slot template restore --name dev-2026-07-03 --from data.dev/prod-sync/prod-local-2026-07-03-090000
```

Point the stable `dev-latest` alias at the template operators should use by default.

```bash
bb dev-slot template alias dev-latest dev-current
```

## Initialize a worktree

Create or choose a Git worktree before running slot initialization.

Worktrees for this project should live under `.worktrees/`.

```bash
git worktree add -b feature/agent-1 .worktrees/probematic-agent-1 HEAD
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
```

Initialization writes generated slot state under `.dev-state/slots/agent-1`.

It also links `data.dev/current-slot` and `data.dev/filestore` inside the worktree.

## Hydrate the slot database

Hydrate the slot from a Datomic template before starting the host app.

```bash
bb dev-slot hydrate agent-1 --template dev-latest
```

Hydration stops the slot Compose services first and refuses to copy while the slot HTTP or nREPL ports still appear active.

Use `--force` only when you know the ports are stale or harmless.

## Start services

Start only the infrastructure services for the claimed slot.

```bash
bb dev-slot up agent-1
```

Check service state without mutating services.

```bash
bb dev-slot ps agent-1
bb dev-slot doctor agent-1
```

## Start the host app

Source the generated environment from the agent worktree before starting the REPL or app.

```bash
cd .worktrees/probematic-agent-1
source /home/ramblurr/src/sno/probematic/.dev-state/slots/agent-1/env.sh
bb dev
```

Each slot's browser-facing services use `<slot>.probematic.localhost` so they remain distinct trustworthy local origins.

The app URL for `agent-1` is `http://agent-1.probematic.localhost:6171`.

The matching OAuth callback URL is `http://agent-1.probematic.localhost:6171/oauth2/callback`.

The smtp4dev URL for `agent-1` is `http://agent-1.probematic.localhost:5102`.

The Datomic console URL for `agent-1` is `http://agent-1.probematic.localhost:8181`.

## Stop and release

Stop the worktree's host app or REPL before releasing its slot.

```bash
bb dev-slot release agent-1
```

Release stops and removes the slot's Compose services, then verifies that all assigned ports are closed.

It removes the slot's Datomic database, smtp4dev data, logs, generated environment, generated secrets, and claim.

It also removes `data.dev/current-slot` and `data.dev/filestore` from the released worktree when those links still point to the released slot's managed targets.

Release preserves the shared `data.dev/filestore`, Datomic templates, and the ignored artifact cache.

The next claimant must run `init` and hydrate from the intended Datomic template before starting the slot.
