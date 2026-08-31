#!/usr/bin/env python3
"""Re-extract every pinned SQC donor slice from Git and verify SHA-256."""
from __future__ import annotations
import argparse, hashlib, json, pathlib, subprocess, sys

def run(*args: str, cwd: pathlib.Path | None = None) -> bytes:
    return subprocess.check_output(args, cwd=cwd, stderr=subprocess.STDOUT)

def ensure_repo(cache: pathlib.Path, repo: str) -> pathlib.Path:
    d = cache / repo.replace("/", "__")
    if not (d / ".git").exists():
        d.mkdir(parents=True, exist_ok=True)
        run("git", "init", "-q", cwd=d)
        run("git", "remote", "add", "origin", f"https://github.com/{repo}.git", cwd=d)
    return d

def canonical_slice(source: bytes, start: int, end: int) -> bytes:
    text = source.decode("utf-8")
    lines = text.splitlines()
    if start < 1 or end < start or end > len(lines):
        raise ValueError(f"invalid range {start}-{end} for {len(lines)} lines")
    return ("\n".join(lines[start - 1:end]) + "\n").encode("utf-8")

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lock", default="ci/composition/COMPOSITION.lock.json")
    ap.add_argument("--cache", default="ci/build/composition-donors")
    ap.add_argument("--offline", action="store_true")
    ns = ap.parse_args()
    lock = json.loads(pathlib.Path(ns.lock).read_text(encoding="utf-8"))
    cache = pathlib.Path(ns.cache)
    failures = []
    current = {}
    for donor in lock["donors"]:
        repo, ref = donor["repo"], donor["ref"]
        d = ensure_repo(cache, repo)
        key = (repo, ref)
        if key not in current:
            if ns.offline:
                try:
                    run("git", "cat-file", "-e", f"{ref}^{{commit}}", cwd=d)
                except Exception:
                    failures.append(f"{donor['id']}: missing {repo}@{ref} in offline cache")
                    continue
            else:
                run("git", "-c", "protocol.version=2", "fetch", "-q", "--depth=1",
                    "--filter=blob:none", "origin", ref, cwd=d)
            current[key] = True
        try:
            source = run("git", "show", f"{ref}:{donor['path']}", cwd=d)
            exact = canonical_slice(source, donor["start"], donor["end"])
            got = hashlib.sha256(exact).hexdigest()
            if got != donor["sha256"]:
                failures.append(f"{donor['id']}: sha256 {got} != {donor['sha256']}")
            elif len(exact) != donor["bytes"]:
                failures.append(f"{donor['id']}: bytes {len(exact)} != {donor['bytes']}")
            else:
                print(f"PASS {donor['id']} {got} {len(exact)}")
        except Exception as exc:
            failures.append(f"{donor['id']}: {exc}")
    if failures:
        for failure in failures:
            print("FAIL", failure, file=sys.stderr)
        return 1
    print(f"COMPOSITION_LOCK_PASS={len(lock['donors'])}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
