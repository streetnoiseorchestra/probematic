# probematic

[![AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--v3--or--later-blue)](./LICENSE)

> "Probe" = "rehearsal" in German. Pronounced PRO-beh ([listen](https://upload.wikimedia.org/wikipedia/commons/f/f9/De-probe.ogg))

Probematic is a web tool that helps an anarchist band manage itself.

It uses Clojure, Datastar, and Web Awesome.

Canonical repo: https://github.com/Ramblurr/probematic

## Development

Run all commands from the project root where `bb.edn` lives.

The project devshell provides Clojure, Babashka, formatting, linting, CSS, and documentation tooling.

Docker is required for the development services.

An editor that connects to nREPL is useful but optional.

This project uses Scoped Commits for commit messages and pull request titles.

Discover all tasks with:

```bash
bb tasks
```

### Start the development server

```bash
# Start Docker-backed development services.
bb dev-services

# Start the application and nREPL.
bb dev

# Optionally rebuild CSS whenever a stylesheet changes.
bb watch-css
```

### Common tasks

```bash
# Run tests.
bb test

# Get test help.
bb test --help

# List test IDs.
bb test --print-test-ids

# Focus one test or namespace.
bb test --focus <TEST-ID>

# Format code or check formatting.
bb fmt
bb fmt:check

# Lint Clojure and Fluent files.
bb lint
bb lint:ftl

# Run the full quality gate, including formatting.
bb qa

# Run the non-mutating CI checks.
bb ci

# Compile CSS after editing stylesheets.
bb css
```



## License

Copyright © 2022-2026 Casey Link <unnamedrambler@gmail.com>

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
