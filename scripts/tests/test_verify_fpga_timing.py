import copy
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from verify_fpga_timing import validate_evidence


def measured_evidence():
    digest = "a" * 64
    return {
        "schemaVersion": 1,
        "status": "measured",
        "target": {"part": "xc7a200tfbg676-2L"},
        "top": "ZirconBoardTop",
        "vivadoVersion": "2026.1",
        "clock": {"name": "clk", "periodNs": 10.0},
        "source": {
            "rtlRevision": "b" * 40,
            "submoduleRevisions": {"ZirconSim": "c" * 40},
        },
        "artifacts": {
            "xdc": {"path": "fpga/zircon-board.xdc", "sha256": digest},
            "timingReport": {"path": "fpga/reports/timing.rpt", "sha256": digest},
            "utilizationReport": {"path": "fpga/reports/utilization.rpt", "sha256": digest},
        },
        "timing": {"setupWnsNs": 0.012, "setupTnsNs": 0.0, "worstHoldSlackNs": 0.021},
        "utilization": {"lut": 100, "ff": 200, "bram": 3, "dsp": 4},
        "command": "vivado -mode batch -source fpga/run.tcl",
    }


class FpgaTimingEvidenceSpec(unittest.TestCase):
    # Test: A complete measured record for the frozen board and 100 MHz passes.
    def test_accepts_complete_nonnegative_timing_evidence(self):
        self.assertEqual(validate_evidence(measured_evidence()), [])

    # Test: A template may never become timing proof merely by having the right part.
    def test_rejects_unverified_template(self):
        evidence = measured_evidence()
        evidence["status"] = "unverified"
        self.assertIn("status must be 'measured'", validate_evidence(evidence))

    # Test: A report for a different FPGA part cannot satisfy this release gate.
    def test_rejects_wrong_target_part(self):
        evidence = measured_evidence()
        evidence["target"]["part"] = "xc7a100tcsg324-1"
        self.assertIn("target.part must be 'xc7a200tfbg676-2L'", validate_evidence(evidence))

    # Test: Negative setup WNS fails even when every other report field is populated.
    def test_rejects_negative_setup_wns(self):
        evidence = measured_evidence()
        evidence["timing"]["setupWnsNs"] = -0.001
        self.assertIn("timing.setupWnsNs must be non-negative", validate_evidence(evidence))

    # Test: Artifact hashes and source revisions must stay reproducible and complete.
    def test_rejects_incomplete_provenance(self):
        evidence = copy.deepcopy(measured_evidence())
        evidence["artifacts"]["xdc"]["sha256"] = "abc"
        evidence["source"]["submoduleRevisions"] = {}
        errors = validate_evidence(evidence)
        self.assertIn("artifacts.xdc.sha256 must be a 64-digit hexadecimal SHA-256", errors)
        self.assertIn("source.submoduleRevisions must be a non-empty object", errors)


if __name__ == "__main__":
    unittest.main()
