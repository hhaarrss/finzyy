"""
Guard: no credential may be committed to the repository.

Scans every tracked file for the shapes real secrets take. Placeholders (`user:password@`,
`<password>`, `your-secret-key-here`) are allowed; real-looking values are not. A failure here
means a secret is about to be (or already is) in git history - rotate it, do not just delete it.

    cd backend && python -m pytest tests/test_no_committed_secrets.py
"""

import re
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Files that legitimately look like they hold keys, and why:
#  - google-services.json: the Firebase *client* config; its API key is public by design
#    (restrict it to the app's package + SHA-1 in Google Cloud instead).
#  - dead-code-archive.md: documents removed code; its only credential is already redacted.
ALLOWED_FILES = {"android/app/google-services.json", "docs/dead-code-archive.md"}

PLACEHOLDER = re.compile(
    r"(?i)(user|username|postgres|nobody)?:(password|pass|pw|<[^>]*>|\*+|\$\{[^}]*\}|choose-[a-z-]+|changeme|xxx+)@"
)

# Obvious fill-in-yourself samples, as used in .env.example.
SAMPLE_WORDS = re.compile(r"(?i)(generate|choose|your-|changeme|example|placeholder|replace)")

PATTERNS = {
    "password inside a connection URL": re.compile(r"[a-z+]+://[^/\s:@\"'<>]+:([^@\s\"'<>/]{4,})@[^\s\"']+"),
    "private key block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "Google API key": re.compile(r"AIza[0-9A-Za-z_\-]{35}"),
    "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
    "long hex/base64 secret assigned to a *_KEY / *_SECRET / *_TOKEN": re.compile(
        r"(?i)\b[A-Z_]*(SECRET|TOKEN|API_?KEY|PASSWORD)[A-Z_]*\s*[=:]\s*[\"']?[A-Za-z0-9+/_\-]{24,}[\"']?\s*$"
    ),
}


def tracked_files():
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO, capture_output=True, check=True
    ).stdout.decode()
    return [f for f in out.split("\0") if f]


class NoCommittedSecretsTest(unittest.TestCase):
    def test_no_secrets_in_tracked_files(self):
        problems = []
        for rel in tracked_files():
            if rel in ALLOWED_FILES or rel.endswith((".png", ".jpg", ".webp", ".jar", ".ttf", ".otf", ".woff2")):
                continue
            try:
                text = (REPO / rel).read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            for lineno, line in enumerate(text.splitlines(), 1):
                for label, pattern in PATTERNS.items():
                    match = pattern.search(line)
                    if not match:
                        continue
                    if PLACEHOLDER.search(line) or "REDACTED" in line or "<" in match.group(0) or SAMPLE_WORDS.search(line):
                        continue
                    # Never echo the value itself.
                    problems.append(f"{rel}:{lineno}: {label}")
        self.assertEqual(problems, [], "Possible committed secret(s):\n  " + "\n  ".join(problems))

    def test_local_secret_files_are_not_tracked(self):
        bad = [
            f for f in tracked_files()
            if re.search(r"(^|/)\.env($|\.)(?!example)|\.jks$|\.keystore$|keystore\.properties$|service-?account.*\.json$|firebase-adminsdk.*\.json$|\.pem$", f)
        ]
        self.assertEqual(bad, [], f"Secret-bearing files are tracked: {bad}")


if __name__ == "__main__":
    unittest.main()
