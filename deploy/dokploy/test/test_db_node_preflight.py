import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CHECK = ROOT / "deploy" / "dokploy" / "verify-db-node.sh"


class DbNodePreflightTest(unittest.TestCase):
    def run_check(
        self, node_output: str, node_id: str = "node-1"
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            docker = Path(directory) / "docker"
            docker.write_text(
                "#!/bin/sh\n"
                "[ \"$#\" -eq 6 ] && "
                "[ \"$1\" = node ] && [ \"$2\" = ls ] && "
                "[ \"$3\" = --filter ] && [ \"$4\" = node.label=role=db ] && "
                "[ \"$5\" = --format ] && "
                "[ \"$6\" = '{{.ID}}|{{.Status}}|{{.Availability}}' ] || exit 64\n"
                "printf '%s' \"$MOCK_NODE_OUTPUT\"\n"
            )
            docker.chmod(0o755)
            environment = os.environ.copy()
            environment["PATH"] = f"{directory}:{environment['PATH']}"
            environment["MOCK_NODE_OUTPUT"] = node_output
            environment["BUILDHOUND_DB_NODE_ID"] = node_id
            return subprocess.run(
                ["sh", str(CHECK)],
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

    def test_accepts_exactly_one_ready_active_db_node(self) -> None:
        result = self.run_check("node-1|Ready|Active\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("role=db on node-1", result.stdout)

    def test_rejects_zero_or_multiple_labelled_nodes(self) -> None:
        for output in ("", "node-1|Ready|Active\nnode-2|Ready|Active\n"):
            with self.subTest(output=output):
                result = self.run_check(output)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("expected exactly one", result.stderr)

    def test_rejects_an_ineligible_labelled_node(self) -> None:
        for output in ("node-1|Down|Active\n", "node-1|Ready|Drain\n"):
            with self.subTest(output=output):
                result = self.run_check(output)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("0 eligible among 1 labelled", result.stderr)

    def test_rejects_a_configured_node_id_mismatch(self) -> None:
        result = self.run_check("node-1|Ready|Active\n", node_id="node-2")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("role=db resolves to node node-1", result.stderr)


if __name__ == "__main__":
    unittest.main()
