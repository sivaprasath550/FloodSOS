#!/usr/bin/env python3
"""
Copies the field-test exports from every USB-connected phone into field-data/.

Alternative to the share sheet. Works with debug builds only (uses `run-as`).
First tap "Export data" on each phone's Stats screen, then run:

    python tools/pull_exports.py
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.floodsos"


def find_adb() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(candidate) if candidate.exists() else (shutil.which("adb") or sys.exit("adb not found"))


def run(adb: str, *args: str) -> bytes:
    return subprocess.run([adb, *args], capture_output=True, check=True).stdout


def main() -> int:
    adb = find_adb()
    out = ROOT / "field-data"
    out.mkdir(exist_ok=True)

    devices = [line.split()[0] for line in run(adb, "devices").decode().splitlines()[1:]
               if line.strip().endswith("device")]
    if not devices:
        sys.exit("No devices connected (check USB debugging).")

    total = 0
    for serial in devices:
        try:
            listing = run(adb, "-s", serial, "shell", "run-as", PACKAGE, "ls", "cache/exports").decode()
        except subprocess.CalledProcessError:
            print(f"{serial}: no exports (tap Export on the Stats screen first)")
            continue
        for name in listing.split():
            if name.endswith(".json"):
                # exec-out keeps the bytes exactly (no shell line-ending changes).
                data = run(adb, "-s", serial, "exec-out", "run-as", PACKAGE, "cat", f"cache/exports/{name}")
                (out / name).write_bytes(data)
                total += 1
                print(f"{serial}: {name}")
    print(f"\n{total} file(s) in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
