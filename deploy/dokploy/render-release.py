#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
from pathlib import Path

MIGRATION_FILE = re.compile(r"^(V([0-9]+)__[A-Za-z0-9_.-]+)\.sql$")


def canonical(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def migration_history(directory: Path) -> list[dict[str, str]]:
    found: list[tuple[int, str, Path]] = []
    for path in directory.rglob("V*.sql"):
        match = MIGRATION_FILE.fullmatch(path.name)
        if match is None:
            raise ValueError(f"invalid Flyway migration filename: {path.name}")
        found.append((int(match.group(2)), match.group(1), path))
    if not found:
        raise ValueError("at least one Flyway migration is required")
    found.sort(key=lambda migration: migration[0])
    versions = [version for version, _, _ in found]
    if len(versions) != len(set(versions)):
        raise ValueError("Flyway migration versions must be unique")
    return [
        {"id": migration_id, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for _, migration_id, path in found
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--server-image", required=True)
    parser.add_argument("--site-image", required=True)
    parser.add_argument("--backup-image", required=True)
    parser.add_argument("--postgres-image", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--volume-guard", required=True)
    parser.add_argument("--migrations-dir", required=True)
    parser.add_argument("--output", default="release.json")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        parser.error("source commit must be full SHA")

    history = migration_history(Path(args.migrations_dir))
    value = {
        "schema": 2,
        "sourceCommit": args.source_commit,
        "serverImage": args.server_image,
        "siteImage": args.site_image,
        "backupImage": args.backup_image,
        "postgresImage": args.postgres_image,
        "manifestSha256": hashlib.sha256(Path(args.manifest).read_bytes()).hexdigest(),
        "volumeGuardSha256": hashlib.sha256(Path(args.volume_guard).read_bytes()).hexdigest(),
        "migrationId": history[-1]["id"],
        "migrationHistory": history,
        "migrationHistorySha256": hashlib.sha256(canonical(history)).hexdigest(),
    }
    Path(args.output).write_bytes(canonical(value))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
