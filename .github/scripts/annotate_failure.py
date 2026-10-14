#!/usr/bin/env python3
"""Republish the tail of a build log as GitHub check annotations.

GitHub's log viewer and its log-download API are not always reachable from wherever a
failure is being investigated; annotations are part of the check-run API and are.
Each annotation message is truncated by GitHub at 4096 characters, so the log is split
into chunks, and the lines that usually matter are repeated first.

Usage: annotate_failure.py <log-file> [tail-lines]
"""

import os
import re
import sys

CHUNK = 3800
INTERESTING = re.compile(r"FAILED|error:|^e: |What went wrong|Caused by|tests? completed|> There were|^gh: ")
MAX_CHUNKS = 22


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

    key = [line for line in lines if INTERESTING.search(line)][-40:]
    if key:
        print("::error title=failure summary::" + escape("\n".join(key)))

    tail = "\n".join(lines[-tail_lines:])
    chunks = [tail[i:i + CHUNK] for i in range(0, len(tail), CHUNK)][:MAX_CHUNKS]
    for index, chunk in enumerate(chunks):
        print(f"::error title=log {index + 1}/{len(chunks)}::{escape(chunk)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
