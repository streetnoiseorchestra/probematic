---
name: dev-slots
description: Use the project parallel dev slot workflow for host-based agents in Git worktrees with isolated Datomic, Redis, smtp4dev, HTTP, and nREPL runtime state. Use when starting, stopping, checking, reattaching, hydrating, or repairing an agent worktree tied to `bb dev-slot`.
---

# Parallel dev slots

Parallel dev slots let host-based agents work in separate Git worktrees while using fixed isolated runtime services under the main checkout's `.dev-state/`.


Run slot commands from the main project root where `bb.edn` lives.


For exact flags and current behavior, run `bb dev-slot --help` and subcommand help such as `bb dev-slot init --help` instead of relying on copied help text.


## Mental model

A slot is a fixed runtime allocation such as `agent-1`, with its own ports, generated secrets, Datomic data, smtp4dev state, and Compose project.


A worktree is a Git checkout under `.worktrees/` where an agent edits code.


The binding is `.dev-state/slots/<slot>/claim.edn`, which records the claimed worktree, branch, user, host, timestamp, and Compose project name.


`init` creates or repairs generated slot files and worktree links such as `data.dev/current-slot` and `data.dev/filestore`.


`release` only removes the claim file.


It does not stop services, delete data, delete the worktree, or remove symlinks.


## Before creating a worktree

If you need to create, remove, or inspect Git worktrees, also load the `using-git-worktrees` skill.


The operator or the agent starting the new development lane is responsible for creating the Git worktree before slot initialization.


Use `.worktrees/` for project worktrees.


Do not rely on `bb dev-slot init` as the worktree creator.


Example:

```bash
git worktree add -b feature/agent-1 .worktrees/probematic-agent-1 HEAD
```


## Start a new agent lane

Check available slots and current claims first.

```bash
bb dev-slot list
bb dev-slot doctor agent-1
```


Create the worktree if it does not already exist.

```bash
git worktree add -b feature/agent-1 .worktrees/probematic-agent-1 HEAD
```


Initialize the slot for that worktree.

```bash
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
```


Link ignored artifacts after the worktree exists.

```bash
bb dev-slot artifacts link agent-1
```


Hydrate Datomic only when you want to replace the slot database from a template.

```bash
bb dev-slot hydrate agent-1 --template prod-latest
```


Start isolated infrastructure.

```bash
bb dev-slot up agent-1
bb dev-slot doctor agent-1
```


Enter the worktree and source the generated environment before starting the host app or REPL.

```bash
bb dev-slot env agent-1 --print-source-command
cd .worktrees/probematic-agent-1
# paste and run the printed source command here
bb dev
```


Start the app through the normal project REPL flow when needed.


## Check status

Use `doctor` for support-grade checks.

```bash
bb dev-slot doctor agent-1
```


Use `ps` for Compose service state.

```bash
bb dev-slot ps agent-1
```


Inspect the claim when ownership is unclear.

```bash
cat .dev-state/slots/agent-1/claim.edn
```


Doctor expects the slot HTTP and nREPL ports to be free before the host app starts, so stop the host app before treating those checks as failures.


## Spin down an agent lane

Stop the host app or REPL first from the worktree session.


Then stop and remove slot infrastructure from the main root.

```bash
bb dev-slot down agent-1
```


Run `down` a second time when Docker state was messy, because it is designed to converge cleanup.

```bash
bb dev-slot down agent-1
```


Release the slot only when the worktree is no longer assigned to that slot.

```bash
bb dev-slot release agent-1
```


Remove the worktree only when the branch lane is finished and the operator agrees.

```bash
git worktree remove .worktrees/probematic-agent-1
```


## Reattach a worktree

A released worktree can be attached again.


Prefer rerunning `init`, because it rewrites generated files and repairs safe symlinks.

```bash
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
bb dev-slot artifacts link agent-1
```


You may attach a different slot to the same worktree only after the old slot is no longer active for that worktree.


Do not intentionally run two slots against one worktree at the same time.


## Repair inconsistent state

If generated files or safe symlinks are stale, rerun `init` for the intended slot and worktree.

```bash
bb dev-slot init agent-1 .worktrees/probematic-agent-1 --branch feature/agent-1
```


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


If containers are stuck or partially removed, run `down` twice and then `doctor`.

```bash
bb dev-slot down agent-1
bb dev-slot down agent-1
bb dev-slot doctor agent-1
```


If hydration refuses because HTTP or nREPL ports are active, stop the host app or REPL instead of forcing by default.


## Guardrails

Do not stage `.dev-state/`, generated slot files, ignored artifacts, or symlinked ignored resources unless the human explicitly asks.


Do not hydrate a slot database while a host app or REPL for that slot is running.


Do not delete the shared main-root `data.dev/filestore` during cleanup.


Do not use `--force` as a routine fix for unclear ownership.
