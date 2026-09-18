#!/usr/bin/env python3

import argparse
import os
import signal
import subprocess
import sys
from pathlib import Path


VALIDATION_MARKER = b"Correct operation validated."


def stop_process_group(process: subprocess.Popen) -> None:
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except ProcessLookupError:
        return
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a simulator and capture its output")
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--stop-after-validation", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()

    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        raise SystemExit("a simulator command is required after --")

    args.log.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    marker_seen = False
    assert process.stdout is not None
    try:
        with args.log.open("wb") as log:
            for line in iter(process.stdout.readline, b""):
                sys.stdout.buffer.write(line)
                sys.stdout.buffer.flush()
                log.write(line)
                log.flush()
                if VALIDATION_MARKER in line:
                    marker_seen = True
                    if args.stop_after_validation:
                        stop_process_group(process)
                        break
    except KeyboardInterrupt:
        stop_process_group(process)
        raise

    if process.poll() is None:
        process.wait()
    if marker_seen and args.stop_after_validation:
        return
    if process.returncode != 0:
        raise SystemExit(process.returncode)


if __name__ == "__main__":
    main()
