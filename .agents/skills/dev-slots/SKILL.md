---
name: dev-slots
description: Use the project parallel dev slot workflow for host-based agents in Git worktrees with isolated Datomic, Redis, smtp4dev, HTTP, and nREPL runtime state, plus one canonical main-checkout `prompts/` directory. Use when working in, starting, stopping, checking, reattaching, hydrating, or repairing an agent worktree tied to `bb dev-slot`.
---

# Parallel dev slots

Parallel dev slots let host-based agents work in separate Git worktrees while using fixed isolated runtime services under the main checkout's `.dev-state/`.

Treat `docs/dev-slots.md` as the canonical operator reference.

This skill is only the agent playbook.

## First steps

Read `docs/dev-slots.md` before changing slot state.

Run command help instead of relying on copied flag lists.

```bash
bb dev-slot --help
bb dev-slot init --help
bb dev-slot hydrate --help
```

If you need to create, remove, or inspect Git worktrees, also load the `using-git-worktrees` skill.

Worktrees for this project should live under `.worktrees/`.

The operator or the agent starting the lane creates the worktree before slot initialization.

## Canonical prompts directory

The main checkout's `prompts/` directory is the single canonical prompts directory for every slot and worktree.

Resolve it from any linked worktree before using prompt documents.

```bash
MAIN_ROOT=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
PROMPTS_ROOT="$MAIN_ROOT/prompts"
```

Read, create, edit, move, and delete prompt documents only under `$PROMPTS_ROOT`.

Never use a slot worktree's `prompts/` directory, even when that directory exists.

Never run prompt-document commands against a relative `prompts/...` path from inside a slot worktree.

Do not copy or synchronize prompt documents into slot worktrees.

When a task changes both code and prompt documents, edit the code in the slot worktree and the prompt documents under `$PROMPTS_ROOT`.

## Common workflow

Create the worktree when needed.

```bash
git worktree add -b feature/agent-1 .worktrees/probematic-agent-1 HEAD
```

Initialize the slot for that worktree.

```bash
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
```

`init` is the normal slot-acquisition command. It claims and initializes the
slot in one operation. Do not run `bb dev-slot claim` first. Standalone `claim`
only records ownership; it does not create generated state or connect the
Firefox profile.

Every agent must run `init` after creating or selecting its worktree and before
hydrating, starting services, or starting the app.

Initialization makes `<worktree>/data.dev/firefox` point to the slot's
persistent Firefox profile. Use that managed path for browser automation; do
not replace the symlink. Before the first migration of an existing worktree
profile, stop Firefox and remove only stale lock files. Initialization refuses
symlinked slot-state paths and conflicting profile locations.

Link ignored artifacts after the worktree exists.

```bash
bb dev-slot artifacts link agent-1
```

Hydrate Datomic only when you intentionally want to replace the slot database.

```bash
bb dev-slot hydrate agent-1 --template dev-latest
```

Start and check isolated infrastructure.

```bash
bb dev-slot up agent-1
bb dev-slot doctor agent-1
```

Enter the worktree and start the host app through its Babashka task. Babashka
tasks automatically apply the environment from `data.dev/current-slot/env.sh`
to their child processes.

```bash
cd .worktrees/probematic-agent-1
bb dev
```

Source the generated environment manually before running `clojure` directly
or invoking a Babashka script file.

```bash
source "$(bb dev-slot env agent-1)"
```

## Status and cleanup

Use `doctor` for support-grade checks.

```bash
bb dev-slot doctor agent-1
```

Use `ps` for Compose service state.

```bash
bb dev-slot ps agent-1
```

Stop the host app or REPL before slot cleanup.

Then stop and remove slot infrastructure.

```bash
bb dev-slot down agent-1
bb dev-slot down agent-1
```

Release the slot only when the worktree is no longer assigned to that slot.

```bash
bb dev-slot release agent-1
```

A released worktree can be attached again by rerunning `init` for the intended slot and worktree.

Release preserves the slot's Firefox profile so later initialization retains
browser logins. For a pre-upgrade worktree, it migrates the existing profile
during release and refuses to continue while Firefox holds a profile lock. If
both profile locations exist, keep both and ask the human which one is
authoritative.

## Repair hints

If generated files or safe symlinks are stale, rerun `init`.

If ignored artifacts are missing or stale, refresh the cache from a complete checkout and relink.

```bash
bb dev-slot artifacts import --source-root .
bb dev-slot artifacts link agent-1
```

If the claim points at the wrong worktree, inspect the claim, make sure no agent is using it, then release and initialize the intended binding.

```bash
cat .dev-state/slots/agent-1/claim.edn
bb dev-slot release agent-1
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
```

Use `--force` only after understanding the current claim or path conflict.

## Guardrails

Do not stage `.dev-state/`, generated slot files, ignored artifacts, or symlinked ignored resources unless the human explicitly asks.

Do not hydrate a slot database while a host app or REPL for that slot is running.

Do not delete the shared main-root `data.dev/filestore` during cleanup.

Do not delete a slot's Firefox profile during cleanup or replace its managed
worktree symlink.

Do not use standalone `claim` as the normal startup step. Use `init`, which
claims and initializes the slot.

Do not intentionally run two slots against one worktree at the same time.
