#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 DATOMIC_SQLITE_DB_PATH" >&2
  exit 2
fi

db_path="$1"
mkdir -p "$(dirname "$db_path")"

echo "Creating SQLite database and schema at $db_path..."
sqlite3 "$db_path" "
-- same as Rails 8.0
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA mmap_size = 134217728; -- 128 megabytes
PRAGMA journal_size_limit = 67108864; -- 64 megabytes
PRAGMA cache_size = 2000;

-- datomic schema
CREATE TABLE IF NOT EXISTS datomic_kvs (
    id TEXT NOT NULL,
    rev INTEGER,
    map TEXT,
    val BYTEA,
    CONSTRAINT pk_id PRIMARY KEY (id)
);"
echo "Database initialization complete."
