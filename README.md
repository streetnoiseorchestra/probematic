# probematic

[![AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--v3--or--later-blue)](./LICENSE)

> "Probe" = "rehearsal" in German. Pronounced PRO-beh ([listen](https://upload.wikimedia.org/wikipedia/commons/f/f9/De-probe.ogg))

Probematic is a web tool that helps an anarchist band manage itself.


It uses Clojure, Datastar, and Web Awesome.

Canonical repo: https://github.com/Ramblurr/probematic

### Pre-req

1. You need Clojure installed as `clj`.
2. You probably want an editor that can connect to the development REPL.

## Run in dev mode

```
# start dev services
docker compose -f docker-compose.dev.yml up -d

# term 1, starts the nrepl
bb repl-portal

# term 2
bb watch-css

# in your editor, open user.clj
# connect to the nrepl
# (dev)
# (go)
```


## License

This software is licensed under the GNU AGPL v3.0 or later.

```
probematic
Copyright (C) 2022-2024 Casey Link

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
```
