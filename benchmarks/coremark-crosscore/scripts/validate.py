#!/usr/bin/env python3

import argparse
import json
import re
from pathlib import Path


EXPECTED_CRCS = {
    "seed_crc": "0x18f2",
    "list_crc": "0xe3c1",
    "matrix_crc": "0x0747",
    "state_crc": "0x8d84",
    "final_crc": "0xcda7",
}

PATTERNS = {
    "cycles": r"Timed cycles\s*:\s*([0-9]+)",
    "score": r"CoreMark/MHz\s*:\s*([0-9]+(?:\.[0-9]+)?)",
    "iterations": r"Iterations\s*:\s*([0-9]+)",
    "compiler": r"Compiler version\s*:\s*(.+)",
    "flags": r"Compiler flags\s*:\s*(.+)",
    "seed_crc": r"seedcrc\s*:\s*(0x[0-9a-fA-F]+)",
    "list_crc": r"crclist\s*:\s*(0x[0-9a-fA-F]+)",
    "matrix_crc": r"crcmatrix\s*:\s*(0x[0-9a-fA-F]+)",
    "state_crc": r"crcstate\s*:\s*(0x[0-9a-fA-F]+)",
    "final_crc": r"crcfinal\s*:\s*(0x[0-9a-fA-F]+)",
}


def parse_log(path: Path, target: str) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace").replace("\r", "")
    result = {"target": target, "log": str(path)}
    errors = []

    for key, pattern in PATTERNS.items():
        match = re.search(pattern, text)
        if match is None:
            errors.append(f"missing {key}")
        else:
            result[key] = match.group(1).strip()

    if "Correct operation validated." not in text:
        errors.append("CoreMark validation marker is missing")

    for key, expected in EXPECTED_CRCS.items():
        if key in result and result[key].lower() != expected:
            errors.append(f"{key} is {result[key]}, expected {expected}")

    if not errors:
        result["cycles"] = int(result["cycles"])
        result["iterations"] = int(result["iterations"])
        result["score"] = float(result["score"])
        calculated = result["iterations"] * 1_000_000 / result["cycles"]
        result["calculated_score"] = calculated
        if abs(calculated - result["score"]) > 0.0015:
            errors.append(
                f"reported score {result['score']:.3f} does not match cycles ({calculated:.3f})"
            )

    if errors:
        raise ValueError(f"{target}: " + "; ".join(errors))

    result["valid"] = True
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate one CoreMark simulator log")
    parser.add_argument("--target", required=True)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    try:
        result = parse_log(args.log, args.target)
    except (OSError, ValueError) as error:
        raise SystemExit(f"validation failed: {error}") from error

    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(
            f"validated {args.target}: {result['score']:.3f} CoreMark/MHz, "
            f"{result['cycles']:,} timed cycles"
        )


if __name__ == "__main__":
    main()
