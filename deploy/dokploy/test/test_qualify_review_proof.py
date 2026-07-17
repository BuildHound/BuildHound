import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
WORKFLOW = ROOT / ".github/workflows/deploy.yml"


class QualifyReviewProofTest(unittest.TestCase):
    def setUp(self):
        self.jq = shutil.which("jq")
        self.assertIsNotNone(self.jq, "jq is required to validate the workflow filter")
        workflow = WORKFLOW.read_text()
        match = re.search(
            r"# review-proof-validator:start\n"
            r"\s*jq -e -s .*?\n"
            r"\s*--arg review_request_event_id \"\$review_request_event_id\" '"
            r"(?P<filter>.*?)\n\s*' \"\$proof_file\" >/dev/null\n"
            r"\s*# review-proof-validator:end",
            workflow,
            re.DOTALL,
        )
        self.assertIsNotNone(match, "review proof validator markers or jq command changed")
        self.filter = match.group("filter")
        self.registry = "ghcr.io/buildhound"
        self.repository = "BuildHound/BuildHound"
        self.pr = 38
        self.sha = "a" * 40
        self.run_id = "123456"
        self.run_attempt = 2
        self.event_id = "987654"

    def proof(self, *, legacy: bool):
        image_digest = "sha256:" + "1" * 64
        site_digest = "sha256:" + "2" * 64
        result = {
            "schema": 1,
            "repository": self.repository,
            "pr": self.pr,
            "headSha": self.sha,
            "runId": self.run_id,
            "attemptId": f"{self.run_id}.{self.run_attempt}",
            "composeId": "compose_1",
            "deploymentId": "deployment_1",
            "serverImage": f"{self.registry}/buildhound-server@{image_digest}",
            "siteImage": f"{self.registry}/buildhound-site@{site_digest}",
        }
        if not legacy:
            result.update(
                reviewRequestEventId=self.event_id,
                runAttempt=self.run_attempt,
                serverDigest=image_digest,
                siteDigest=site_digest,
            )
        return result

    def validate(self, *proofs, legacy: bool):
        command = [
            self.jq, "-e", "-s",
            "--arg", "legacy_bootstrap", str(legacy).lower(),
            "--arg", "registry", self.registry,
            "--arg", "repository", self.repository,
            "--argjson", "pr", str(self.pr),
            "--arg", "sha", self.sha,
            "--arg", "run_id", self.run_id,
            "--argjson", "run_attempt", str(self.run_attempt),
            "--arg", "review_request_event_id", self.event_id,
            self.filter,
        ]
        return subprocess.run(
            command,
            input="".join(json.dumps(proof) + "\n" for proof in proofs),
            text=True,
            capture_output=True,
            check=False,
        )

    def test_embedded_validator_accepts_valid_legacy_and_modern_proofs(self):
        for legacy in (True, False):
            with self.subTest(legacy=legacy):
                result = self.validate(self.proof(legacy=legacy), legacy=legacy)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_embedded_validator_rejects_duplicate_and_tampered_proofs(self):
        modern = self.proof(legacy=False)
        duplicate = self.validate(modern, modern, legacy=False)
        self.assertNotEqual(duplicate.returncode, 0, duplicate.stdout + duplicate.stderr)

        tampered = self.proof(legacy=False)
        tampered["siteDigest"] = "sha256:" + "f" * 64
        invalid = self.validate(tampered, legacy=False)
        self.assertNotEqual(invalid.returncode, 0, invalid.stdout + invalid.stderr)

    def test_embedded_validator_rejects_cross_schema_modes(self):
        for proof_legacy, validator_legacy in ((True, False), (False, True)):
            with self.subTest(
                proof_legacy=proof_legacy,
                validator_legacy=validator_legacy,
            ):
                result = self.validate(
                    self.proof(legacy=proof_legacy),
                    legacy=validator_legacy,
                )
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
