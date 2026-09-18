#!/usr/bin/env python3

import argparse
import csv
from pathlib import Path

from validate import parse_log


TARGETS = [
    ("zircon", "Zircon", "RV32IMAF / ILP32F"),
    ("xiangshan-yanqihu", "XiangShan Yanqihu", "RV64GC / LP64D"),
    ("boom-small", "BOOM Small", "RV64GC / LP64D"),
    ("boom-medium", "BOOM Medium", "RV64GC / LP64D"),
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate CoreMark comparison reports")
    parser.add_argument("--results-dir", required=True, type=Path)
    parser.add_argument("--allow-missing-zircon", action="store_true")
    parser.add_argument("--zircon-baseline", type=float)
    args = parser.parse_args()

    args.results_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for target, display_name, isa in TARGETS:
        log_path = args.results_dir / f"{target}.log"
        if target == "zircon" and not log_path.exists() and args.allow_missing_zircon:
            if args.zircon_baseline is None:
                raise SystemExit("--zircon-baseline is required when the Zircon log is missing")
            rows.append(
                {
                    "target": target,
                    "name": display_name,
                    "isa": isa,
                    "score": args.zircon_baseline,
                    "cycles": "not retained",
                    "iterations": 30,
                    "validation": "user-confirmed baseline",
                }
            )
            continue
        try:
            parsed = parse_log(log_path, target)
        except (OSError, ValueError) as error:
            raise SystemExit(f"cannot report {target}: {error}") from error
        rows.append(
            {
                "target": target,
                "name": display_name,
                "isa": isa,
                "score": parsed["score"],
                "cycles": parsed["cycles"],
                "iterations": parsed["iterations"],
                "validation": "CRC valid",
            }
        )

    baseline = next(row["score"] for row in rows if row["target"] == "zircon")
    csv_path = args.results_dir / "results.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["target", "coremark_per_mhz", "timed_cycles", "iterations", "relative_to_zircon", "validation"])
        for row in rows:
            relative = (row["score"] / baseline - 1.0) * 100.0
            writer.writerow(
                [row["target"], f"{row['score']:.3f}", row["cycles"], row["iterations"], f"{relative:+.1f}%", row["validation"]]
            )

    report_path = args.results_dir / "report.md"
    with report_path.open("w", encoding="utf-8") as output:
        output.write("# CoreMark cross-core results\n\n")
        output.write("| Processor | CoreMark/MHz | Timed cycles | ISA / ABI | Relative to Zircon | Validation |\n")
        output.write("| --- | ---: | ---: | --- | ---: | --- |\n")
        for row in rows:
            relative = (row["score"] / baseline - 1.0) * 100.0
            cycles = f"{row['cycles']:,}" if isinstance(row["cycles"], int) else row["cycles"]
            output.write(
                f"| {row['name']} | {row['score']:.3f} | {cycles} | {row['isa']} | "
                f"{relative:+.1f}% | {row['validation']} |\n"
            )
        output.write("\n")
        output.write("Scores use 30 iterations, `-O2`, simulator seed 1, and an `mcycle` interval around `iterate()`. ")
        output.write("The RV64 targets share one workload build; Zircon uses the analogous RV32 build. ")
        output.write("CoreMark/MHz measures per-cycle performance, not area or energy efficiency.\n")

    print(f"wrote {csv_path}")
    print(f"wrote {report_path}")


if __name__ == "__main__":
    main()
