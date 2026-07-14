import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]


class ReleaseRendererTest(unittest.TestCase):
    def test_render_release_binds_numeric_ordered_migration_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migrations = root / "migrations"
            migrations.mkdir()
            (migrations / "V10__later.sql").write_text("select 10;\n")
            (migrations / "V2__earlier.sql").write_text("select 2;\n")
            manifest = root / "stack.yaml"
            manifest.write_text("services: {}\n")
            staging_manifest = root / "staging-stack.yaml"
            staging_manifest.write_text("services:\n  staging: {}\n")
            guard = root / "guard.sh"
            guard.write_text("#!/bin/sh\n")
            output = root / "release.json"

            subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "deploy/dokploy/render-release.py"),
                    "--source-commit",
                    "a" * 40,
                    "--server-image",
                    "ghcr.io/x/server@sha256:" + "1" * 64,
                    "--site-image",
                    "ghcr.io/x/site@sha256:" + "2" * 64,
                    "--backup-image",
                    "ghcr.io/x/backup@sha256:" + "3" * 64,
                    "--postgres-image",
                    "ghcr.io/x/db@sha256:" + "4" * 64,
                    "--production-manifest",
                    str(manifest),
                    "--staging-manifest",
                    str(staging_manifest),
                    "--volume-guard",
                    str(guard),
                    "--migrations-dir",
                    str(migrations),
                    "--output",
                    str(output),
                ],
                check=True,
            )

            release = json.loads(output.read_text())
            self.assertEqual(release["schema"], 3)
            self.assertEqual(
                release["productionManifestSha256"],
                hashlib.sha256(manifest.read_bytes()).hexdigest(),
            )
            self.assertEqual(
                release["stagingManifestSha256"],
                hashlib.sha256(staging_manifest.read_bytes()).hexdigest(),
            )
            self.assertEqual(
                [item["id"] for item in release["migrationHistory"]],
                ["V2__earlier", "V10__later"],
            )
            self.assertEqual(
                release["migrationHistory"][0]["sha256"],
                hashlib.sha256(b"select 2;\n").hexdigest(),
            )
            subprocess.run(
                [
                    "bash",
                    str(ROOT / "deploy/dokploy/dokploy.sh"),
                    "release-id",
                    str(output),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )


if __name__ == "__main__":
    unittest.main()
