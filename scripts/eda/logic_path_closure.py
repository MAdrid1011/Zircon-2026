"""Check that every old violating path family has a scoped RTL-change claim."""

import argparse
import json
from pathlib import Path


def summarize(audit, claims):
    families = audit["families"]
    by_name = {family["name"]: family for family in families}
    if (len(families) != audit["family_count"] or len(by_name) != len(families) or
            sum(f["count"] for f in families) != audit["path_count"]):
        raise ValueError("Audit family inventory is incomplete or duplicated")

    covered = {}
    for claim in claims.get("source_groups", []):
        source = claim["source"]
        prefixes = claim.get("endpoint_prefixes", [])
        exceptions = set(claim.get("except_families", []))
        matches = [name for name in by_name if name.startswith(source + " -> ")]
        if not matches or not exceptions.issubset(matches):
            raise ValueError(f"Unknown source group or exception: {source}")
        matches = [name for name in matches if name not in exceptions and (
            not prefixes or any(name.split(" -> ", 1)[1].startswith(prefix) for prefix in prefixes)
        )]
        if not matches or not claim.get("evidence"):
            raise ValueError(f"Empty or unsupported source claim: {source}")
        for name in matches:
            if name in covered:
                raise ValueError(f"Overlapping claims: {name}")
            covered[name] = claim

    for claim in claims.get("families", []):
        name = claim["name"]
        if name not in by_name or name in covered or not claim.get("evidence"):
            raise ValueError(f"Unknown, overlapping, or unsupported family claim: {name}")
        covered[name] = claim

    modified = [family for family in families if family["name"] in covered and
                covered[family["name"]].get("counts_as_modified", True)]
    modified_names = {family["name"] for family in modified}
    open_families = [family for family in families if family["name"] not in modified_names]
    return {
        "baseline_families": audit["family_count"],
        "baseline_endpoints": audit["path_count"],
        "modified_families": len(modified),
        "modified_endpoints": sum(family["count"] for family in modified),
        "open_families": len(open_families),
        "open_endpoints": sum(family["count"] for family in open_families),
        "open": [{"name": family["name"], "count": family["count"]} for family in open_families],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--claims", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    claims = json.loads(args.claims.read_text())
    if claims.get("baseline") != args.audit.name:
        parser.error("Claims do not name this audit baseline")
    result = summarize(json.loads(args.audit.read_text()), claims)
    if args.output:
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f'{result["modified_families"]}/{result["baseline_families"]} families modified; '
          f'{result["open_families"]} families and {result["open_endpoints"]} endpoints remain open')
    if args.require_complete and result["open_families"]:
        parser.exit(1, "Full-family RTL-change gate failed; do not start synthesis\n")


if __name__ == "__main__":
    main()
