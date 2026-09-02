#!/usr/bin/env python3
"""Validate committed metadata for a Zircon FPGA post-route timing run."""

import argparse
import json
import re
import sys
from pathlib import Path


TARGET_PART = "xc7a200tfbg676-2L"
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
REVISION = re.compile(r"^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$")


def nested(mapping, *keys):
    current = mapping
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def validate_evidence(evidence):
    """Return all schema and release-gate violations for one evidence record."""
    errors = []
    if not isinstance(evidence, dict):
        return ["evidence must be a JSON object"]

    if evidence.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if evidence.get("status") != "measured":
        errors.append("status must be 'measured'")
    if nested(evidence, "target", "part") != TARGET_PART:
        errors.append(f"target.part must be '{TARGET_PART}'")
    if not nonempty_string(evidence.get("top")):
        errors.append("top must be a non-empty string")
    if not nonempty_string(evidence.get("vivadoVersion")):
        errors.append("vivadoVersion must be a non-empty string")
    if nested(evidence, "clock", "name") != "clk":
        errors.append("clock.name must be 'clk'")
    if nested(evidence, "clock", "periodNs") != 10.0:
        errors.append("clock.periodNs must be 10.0")

    revision = nested(evidence, "source", "rtlRevision")
    if not isinstance(revision, str) or not REVISION.fullmatch(revision):
        errors.append("source.rtlRevision must be a 40- or 64-digit hexadecimal revision")
    submodules = nested(evidence, "source", "submoduleRevisions")
    if not isinstance(submodules, dict) or not submodules:
        errors.append("source.submoduleRevisions must be a non-empty object")
    elif any(not isinstance(value, str) or not REVISION.fullmatch(value)
             for value in submodules.values()):
        errors.append("source.submoduleRevisions entries must be 40- or 64-digit hexadecimal revisions")

    for artifact in ("xdc", "timingReport", "utilizationReport"):
        path = nested(evidence, "artifacts", artifact, "path")
        digest = nested(evidence, "artifacts", artifact, "sha256")
        if not nonempty_string(path):
            errors.append(f"artifacts.{artifact}.path must be a non-empty string")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            errors.append(f"artifacts.{artifact}.sha256 must be a 64-digit hexadecimal SHA-256")

    for field in ("setupWnsNs", "setupTnsNs", "worstHoldSlackNs"):
        value = nested(evidence, "timing", field)
        if not number(value):
            errors.append(f"timing.{field} must be numeric")
    setup_wns = nested(evidence, "timing", "setupWnsNs")
    if number(setup_wns) and setup_wns < 0:
        errors.append("timing.setupWnsNs must be non-negative")

    for field in ("lut", "ff", "bram", "dsp"):
        value = nested(evidence, "utilization", field)
        if not number(value) or value < 0:
            errors.append(f"utilization.{field} must be a non-negative number")
    if not nonempty_string(evidence.get("command")):
        errors.append("command must be a non-empty string")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path,
                        help="JSON evidence generated from one post-route Vivado run")
    arguments = parser.parse_args(argv)
    try:
        evidence = json.loads(arguments.evidence.read_text(encoding="utf-8"))
    except OSError as error:
        parser.error(f"cannot read {arguments.evidence}: {error}")
    except json.JSONDecodeError as error:
        parser.error(f"invalid JSON in {arguments.evidence}: {error}")

    errors = validate_evidence(evidence)
    if errors:
        for error in errors:
            print(f"FPGA timing evidence error: {error}", file=sys.stderr)
        return 1
    print(f"FPGA timing evidence accepted: {arguments.evidence}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
