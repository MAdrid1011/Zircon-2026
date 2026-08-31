import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "static_area_report.py"
SPEC = importlib.util.spec_from_file_location("static_area_report", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def manifest(completeness="partial"):
    return {
        "schema_version": 1,
        "design": "test",
        "revision": "abc",
        "configuration": "unit",
        "completeness": completeness,
        "known_omissions": ["unfinished"] if completeness == "partial" else [],
        "storage": [
            {
                "name": "array",
                "class": "register_payload",
                "entries": 4,
                "bits_per_entry": 8,
                "instances": 2,
                "replication_factor": 3,
                "source": "unit test",
            }
        ],
        "logic": [
            {
                "name": "mux",
                "metric": "mux_input_bits",
                "units": 2,
                "width": 8,
                "fanin": 4,
                "source": "unit test",
            }
        ],
    }


class StaticAreaReportTest(unittest.TestCase):
    def test_computes_logical_replicated_and_logic_proxies(self):
        data = manifest()
        MODULE.validate_manifest(data)
        storage = MODULE.storage_totals(data)
        logic = MODULE.logic_totals(data)
        self.assertEqual(storage["logical::register_payload"], 64)
        self.assertEqual(storage["replicated::register_payload"], 192)
        self.assertEqual(storage["replicated::total"], 192)
        self.assertEqual(logic["mux_input_bits"], 64)

    def test_rejects_duplicate_names_and_silent_partial_manifests(self):
        data = manifest()
        data["storage"].append(dict(data["storage"][0]))
        with self.assertRaises(MODULE.ManifestError):
            MODULE.validate_manifest(data)

        data = manifest()
        data["known_omissions"] = []
        with self.assertRaises(MODULE.ManifestError):
            MODULE.validate_manifest(data)

    def test_marks_partial_and_complete_reports_explicitly(self):
        partial = MODULE.render_report(manifest(), manifest())
        self.assertIn("Sign-off readiness: **PARTIAL**", partial)
        self.assertIn("Missing structures must not be treated as zero-area wins", partial)

        complete = MODULE.render_report(
            manifest(completeness="complete"),
            manifest(completeness="complete"))
        self.assertIn("Sign-off readiness: **READY**", complete)


if __name__ == "__main__":
    unittest.main()
