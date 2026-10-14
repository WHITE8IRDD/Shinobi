#!/usr/bin/env python3
"""Republish the useful tail of a job log as GitHub check annotations.

GitHub's log viewer and log-download API are not always reachable when investigating a
failure. Check annotations are available through the check-run API. Messages are limited
to 4096 characters, so the log is split into smaller chunks.

Usage: annotate_failure.py <log-file> [tail-lines]
"""

import os
import re
import sys

CHUNK = 3800
MAX_CHUNKS = 22
INTERESTING = re.compile(
    r"FAILED|error:|^e: |What went wrong|Caused by|tests? completed|> There were|^gh: "
)


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "build.log"
    tail_lines = int(sys.argv[2]) if len(sys.argv) > 2 else 1500
    if not os.path.exists(path):
        print(f"::error::no log at {path}")
        return 0

    with open(path, "rb") as handle:
        lines = handle.read().decode("utf-8", "replace").splitlines()

    key_lines = [line for line in lines if INTERESTING.search(line)][-40:]
    if key_lines:
        print("::error title=failure summary::" + escape("\n".join(key_lines)))

    tail = "\n".join(lines[-tail_lines:])
    chunks = [tail[i : i + CHUNK] for i in range(0, len(tail), CHUNK)][:MAX_CHUNKS]
    for index, chunk in enumerate(chunks, start=1):
        print(f"::error title=log {index}/{len(chunks)}::{escape(chunk)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
