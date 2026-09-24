"""Unit tests for the constrained Nangate45 synthesis and timing-report gate."""

import json
from pathlib import Path
import re
import tempfile
import unittest

from adder_mapping import AdderMapping
from eda.gate_equivalence import boundaries, file_sha256, prove
from logic_only_sta import _violating_endpoint_count, _worst_data_arrival
from nangate_memories import DUAL_PORT_MACROS, generate as generate_nangate_memories
from openroad_resizer import (
    DEFAULT_MAX_REPAIRS_PER_PASS,
    DEFAULT_SLEW_MARGIN,
    run as run_openroad_resizer,
)
from synthesize_core import (
    build_yosys_script,
    elaboration_command,
    requested_targets,
    target_delay_ps,
    validate_no_simulation_debug,
)
from timing_reports import (
    classify_endpoint_modules,
    count_violations,
    parse_dff_registers,
    parse_endpoint_inventory,
    parse_repair_counts,
    parse_ram_edge_paths,
    require_electrically_clean,
    summarize_target,
)


class TimingFlowTests(unittest.TestCase):
    def test_every_external_ram_binding_uses_bsg_rising_edge_models(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rtl = root / "rtl"
            rtl.mkdir()
            (rtl / "SinglePortMaskedRam_16_1_25.sv").write_text(
                "module SinglePortMaskedRam_16_1_25(input clock, enable, write, "
                "input [3:0] address, input [24:0] dataIn, output [24:0] dataOut); "
                "endmodule\n"
            )

            result = generate_nangate_memories(rtl, root / "memory")
            derived = {
                name: result["libraries"][name]
                for name, _, _, _ in DUAL_PORT_MACROS
            }

            self.assertEqual(len(derived), len(DUAL_PORT_MACROS))
            for name, item in derived.items():
                liberty = Path(item["path"]).read_text()
                lef = Path(item["lef"]).read_text()
                self.assertEqual(liberty.count("timing_type : rising_edge"), 2)
                self.assertNotIn("falling_edge", liberty)
                self.assertIn("BSG Fakeram", liberty)
                self.assertTrue(name.startswith("fakeram45_1rw1r_"))
                self.assertIn(f"MACRO {name}", lef)
                self.assertIn("PIN clock", lef)

            resources = Path(__file__).parents[2] / "src/main/resources"
            wrappers = [
                resources / "BsgFakeram1RW1R_25.sv",
                resources / "BsgFakeram1RW1R_32.sv",
                *sorted(resources.glob("PredictorBsgFakeram_*.sv")),
            ]
            for wrapper in wrappers:
                text = wrapper.read_text()
                self.assertIn("fakeram45_1rw1r_", text)
                self.assertNotIn("openram45_", text)
                self.assertIn("BUF_X1 addr0_buffer", text)
                self.assertIn(".addr0(addr0_buffered)", text)

    def test_active_nangate_platform_contains_only_bsg_sram_views(self):
        platform = Path(__file__).parents[2] / "eda/platforms/nangate45"
        metadata = json.loads((platform / "platform.json").read_text())
        memory_lefs = [
            item["file"]
            for item in metadata["physical"]["lef_files"]
            if "ram" in item["file"].lower()
        ]

        self.assertTrue(memory_lefs)
        self.assertTrue(all("fakeram" in path.lower() for path in memory_lefs))
        self.assertFalse(any("openram" in path.lower() for path in memory_lefs))

        runner = (Path(__file__).parent / "synthesize_core.py").read_text()
        self.assertIn('if "ram" in name.lower() and not name.startswith("fakeram45_")', runner)

    def test_logic_only_summary_records_worst_arrival_and_all_violating_endpoints(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            paths = root / "paths.rpt"
            endpoints = root / "endpoints.rpt"
            paths.write_text(
                "  1.250000 data arrival time\n"
                "  0.900000 data arrival time\n"
            )
            endpoints.write_text(
                "_a_/D 0.9 1.2 -0.3 (VIOLATED)\n"
                "_b_/D 0.9 1.0 -0.1 (VIOLATED)\n"
                "_c_/D 0.9 0.8 0.1\n"
            )

            self.assertEqual(_worst_data_arrival(paths), 1.25)
            self.assertEqual(_violating_endpoint_count(endpoints), 2)

    def test_equivalence_cuts_fakeram_and_openram_as_memory_boundaries(self):
        memory_ports = {
            "clk0": "input",
            "addr0": "input",
            "dout0": "output",
        }
        module = {
            "ports": {"clock": {"bits": [1]}},
            "cells": {
                "fakeram": {
                    "type": "fakeram45_32x32",
                    "port_directions": memory_ports,
                    "connections": {"clk0": [1], "addr0": [2], "dout0": [3]},
                },
                "openram": {
                    "type": "openram45_1rw1r_16x32",
                    "port_directions": memory_ports,
                    "connections": {"clk0": [1], "addr0": [4], "dout0": [5]},
                },
            },
        }

        registers, memories = boundaries(module)

        self.assertEqual(registers, {})
        self.assertEqual(set(memories), {"fakeram", "openram"})

    def test_equivalence_hashes_large_inputs_incrementally(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.json"
            path.write_bytes(b"mapped-design")

            self.assertEqual(
                file_sha256(path),
                "68bbd055c1b06b5013b6bc74bf567fefd4cf0c332f72d947a576d351ae0a956a",
            )

    def test_equivalence_rejects_nonpositive_worker_count_before_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            design = root / "design.json"
            design.write_text('{"modules":{"Top":{"ports":{},"cells":{},"netnames":{}}}}')

            def copy_design(argv, log_path):
                output = root / ("gold-flat.json" if "gold-flat.ys" in str(argv) else "gate-flat.json")
                output.write_text(design.read_text())
                Path(log_path).write_text("flattened\n")
                return "flattened\n"

            with self.assertRaisesRegex(ValueError, "worker count"):
                prove(design, design, "Top", root / "cells.lib", root, copy_design, "yosys", workers=0)

    def test_target_sweep_is_ordered_and_converts_to_picoseconds(self):
        self.assertEqual(requested_targets(sweep=True), [1.0])
        self.assertEqual(target_delay_ps(1.0), 1000)
        with self.assertRaises(ValueError):
            target_delay_ps(0)

    def test_synthesis_elaboration_excludes_simulation_debug(self):
        command = elaboration_command(Path("generated rtl"))
        self.assertIn("--bsg", command)
        self.assertNotIn("--simulation", command)

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            clean = root / "Core.sv"
            debug = root / "DebugCore.sv"
            clean.write_text("module ZirconCore(input clock); endmodule\n")
            debug.write_text("module ZirconCore(output io_debug_retire); endmodule\n")
            validate_no_simulation_debug([clean])
            with self.assertRaisesRegex(RuntimeError, "Simulation-only debug logic"):
                validate_no_simulation_debug([clean, debug])

    def test_adder_mapping_builds_pre_and_post_flatten_commands(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            mapping = AdderMapping(("BLevelPAdder32",), delay_ps=900)

            before_flatten = mapping.commands(
                root / "Nangate.lib", root / "abc.constr", root, stage="pre_flatten"
            )
            after_flatten = mapping.commands(
                root / "Nangate.lib", root / "abc.constr", root, stage="post_flatten"
            )

            self.assertIn("-constr", before_flatten)
            self.assertIn("BLevelPAdder32", before_flatten)
            self.assertNotIn("BLevelPAdder32", after_flatten)
            self.assertIn("-constr", after_flatten)
            self.assertIn("-D 900", after_flatten)
            self.assertIn(
                "buffer; upsize -D 900; dnsize -D 900",
                (root / "adder.abc").read_text(),
            )

    def test_synthesis_script_direct_maps_blevel_then_constrains_remaining_logic(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            adder = root / "Adder.sv"
            core = root / "Core.sv"
            adder.write_text("module BLevelPAdder32(input a, output y); assign y = a; endmodule\n")
            core.write_text("module ZirconCore(input a, output y); assign y = a; endmodule\n")

            script = build_yosys_script(
                rtl_sources=[adder, core],
                wrapper_files=[],
                top="ZirconCore",
                liberty_inputs=[root / "Nangate.lib"],
                constraints=root / "abc.constr",
                output_dir=root,
                delay_ps=900,
            )

            preserve = script.index("setattr -mod -set keep_hierarchy")
            synth = script.index("synth -top ZirconCore -noabc")
            direct_map = script.index("abc -liberty", synth)
            flatten = script.index("flatten", direct_map)
            full_map = script.index("abc -liberty", flatten)
            self.assertLess(preserve, synth)
            self.assertLess(synth, direct_map)
            self.assertLess(direct_map, flatten)
            self.assertLess(flatten, full_map)
            self.assertIn("-constr", script)
            self.assertIn("-D 900", script)
            self.assertIn("BLevelPAdder32", script)
            self.assertIn("buffer; upsize -D 900; dnsize -D 900", (root / "adder.abc").read_text())

    def test_resizer_uses_standard_physical_repair_without_timing_exceptions(self):
        tcl = (Path(__file__).parent / "openroad_resizer.tcl").read_text()
        for command in ("rtl_macro_placer", "global_placement", "repair_design", "repair_timing"):
            self.assertIn(command, tcl)
        self.assertIn(
            "global_placement -timing_driven -routability_driven",
            tcl,
        )
        for forbidden in ("set_false_path", "set_multicycle_path", "set_max_delay"):
            self.assertNotIn(forbidden, tcl)
        self.assertIn("cap_count == 0", tcl)
        self.assertIn("slew_count == 0", tcl)
        self.assertIn("placement_density * 100.0", tcl)
        self.assertIn("-max_count 2000000", tcl)

    def test_logic_only_sta_explicitly_skips_placement_and_parasitic_estimation(self):
        tcl = (Path(__file__).parent / "logic_only_sta.tcl").read_text()
        self.assertIn("read_lef", tcl)
        self.assertIn("no_floorplan no_placement no_parasitics", tcl)
        self.assertNotIn("initialize_floorplan", tcl)
        self.assertNotIn("estimate_parasitics", tcl)
        self.assertIn("logic-only-violating-paths-summary.rpt", tcl)
        self.assertIn("-format summary", tcl)

        runner = (Path(__file__).parent / "synthesize_core.py").read_text()
        self.assertIn("run_logic_only_sta", runner)
        self.assertLess(runner.index("run_logic_only_sta("), runner.index("run_openroad_resizer("))

    def test_synthesis_entrypoint_can_stop_after_complete_logic_mapping(self):
        source = (Path(__file__).parent / "synthesize_core.py").read_text()

        self.assertIn('"--logic-only"', source)
        logic_sta = source.index("logic_only = run_logic_only_sta(")
        stop = source.index("if args.logic_only:", logic_sta)
        physical = source.index("physical = run_openroad_resizer(", stop)
        self.assertLess(logic_sta, stop)
        self.assertLess(stop, physical)
        self.assertIn('result["mode"] = "logic-only"', source[stop:physical])
        self.assertIn("continue", source[stop:physical])

    def test_resizer_runs_final_electrical_repair_after_setup_repair(self):
        tcl = (Path(__file__).parent / "openroad_resizer.tcl").read_text()
        setup_repair = tcl.index("repair_timing -setup")
        final_repair = tcl.index('puts "RESIZER_STAGE repair_design_final"')
        final_slew_margin = tcl.index("repair_design -slew_margin $slew_margin")
        convergence_repair = tcl.index('puts "RESIZER_STAGE repair_design_final_convergence"')
        electrical_report = tcl.index("report_check_types -max_capacitance")

        self.assertIn("if {$run_setup_repair}", tcl)
        self.assertIn("repair_design -slew_margin $slew_margin", tcl)
        self.assertIn(
            "repair_timing -setup -max_repairs_per_pass $max_repairs_per_pass",
            tcl,
        )
        self.assertEqual(len(re.findall(r"(?m)^repair_design(?:\s|$)", tcl)), 3)
        self.assertLess(setup_repair, final_repair)
        self.assertLess(final_repair, final_slew_margin)
        self.assertLess(final_slew_margin, convergence_repair)
        self.assertLess(convergence_repair, electrical_report)
        self.assertLess(final_slew_margin, electrical_report)
        self.assertLess(final_repair, electrical_report)
        self.assertEqual(DEFAULT_SLEW_MARGIN, 70)
        self.assertEqual(DEFAULT_MAX_REPAIRS_PER_PASS, 1)

    def test_resizer_rejects_invalid_placement_density_before_tool_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            for density in (0.0, 1.0, -0.1, 1.1):
                with self.assertRaisesRegex(ValueError, "Placement density"):
                    run_openroad_resizer(
                        netlist=Path(temp) / "unused.v",
                        target_ns=1.0,
                        platform_dir=Path(__file__).parents[2] / "eda/platforms/nangate45",
                        output_dir=Path(temp) / "output",
                        liberty_files=[],
                        image="unused",
                        placement_density=density,
                    )

    def test_resizer_rejects_invalid_repair_batch_before_tool_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            for repairs in (0, -1, 1.5):
                with self.assertRaisesRegex(ValueError, "repairs per pass"):
                    run_openroad_resizer(
                        netlist=Path(temp) / "unused.v",
                        target_ns=1.0,
                        platform_dir=Path(__file__).parents[2] / "eda/platforms/nangate45",
                        output_dir=Path(temp) / "output",
                        liberty_files=[],
                        image="unused",
                        placement_density=0.6,
                        max_repairs_per_pass=repairs,
                    )

    def test_synthesis_entrypoint_forwards_and_records_placement_density(self):
        source = (Path(__file__).parent / "synthesize_core.py").read_text()

        self.assertIn('"--placement-density"', source)
        self.assertIn("placement_density=args.placement_density", source)
        self.assertIn("max_repairs_per_pass=args.max_repairs_per_pass", source)
        self.assertIn(
            'result["placement_density"] = physical["placement_density"]',
            source,
        )
        self.assertIn(
            'result["timing_driven_placement"] = physical["timing_driven_placement"]',
            source,
        )
        self.assertIn(
            'result["routability_driven_placement"] = physical["routability_driven_placement"]',
            source,
        )

    def test_public_entrypoints_always_run_formal_setup_repair(self):
        directory = Path(__file__).parent
        for name in ("synthesize_core.py", "openroad_resizer.py"):
            source = (directory / name).read_text()
            self.assertNotIn('add_argument("--skip-setup-repair"', source)
        self.assertIn("setup_repair=True", (directory / "synthesize_core.py").read_text())
        self.assertIn("setup_repair=True", (directory / "openroad_resizer.py").read_text())

    def test_endpoint_inventory_keeps_each_violating_endpoint(self):
        report = """Startpoint                           Endpoint                                  Slack
-----------------------------------------------------------------------------------
_a_/Q (DFF_X1)                       _b_/D (DFF_X1)                          -0.12500
_c_/Q (DFF_X1)                       _d_/D (DFF_X1)                          -0.02500
"""
        endpoints = parse_endpoint_inventory(report)
        self.assertEqual(len(endpoints), 2)
        self.assertEqual(endpoints[0]["startpoint"], "_a_/Q")
        self.assertEqual(endpoints[0]["endpoint"], "_b_/D")
        self.assertAlmostEqual(endpoints[0]["slack_ns"], -0.125)

    def test_endpoint_inventory_parses_openroad_end_format(self):
        report = """max_delay/setup group core_clock

Endpoint                                   Delay       Delay       Slack
------------------------------------------------------------------------
_a_/D (DFF_X1)                         0.970858    8.601694   -7.630836 (VIOLATED)
_b_/D (DFF_X1)                         0.970000    8.599000   -7.629000 (VIOLATED)
"""
        endpoints = parse_endpoint_inventory(report)

        self.assertEqual(len(endpoints), 2)
        self.assertIsNone(endpoints[0]["startpoint"])
        self.assertEqual(endpoints[0]["endpoint"], "_a_/D")
        self.assertAlmostEqual(endpoints[0]["slack_ns"], -7.630836)

    def test_anonymous_dff_endpoints_map_back_to_module_registers(self):
        netlist = """module ZirconCore;
  DFF_X1 _42_ (
    .CK(clock),
    .D(_1_),
    .Q(\\middleend.renameStageEntries_1_context_instruction_op [4]),
    .QN(_2_)
  );
  DFF_X2 _43_ (.CK(clock), .D(_3_), .Q(frontend_state));
endmodule
"""
        endpoints = [
            {"endpoint": "_42_/D", "slack_ns": -0.5, "startpoint": None},
            {"endpoint": "_43_/D", "slack_ns": -0.2, "startpoint": None},
            {"endpoint": "backend.ram/wmask0[0]", "slack_ns": -0.1, "startpoint": None},
        ]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "repaired.v"
            path.write_text(netlist)
            registers = parse_dff_registers(path, {"_42_", "_43_"})

        self.assertEqual(
            registers["_42_"],
            "middleend.renameStageEntries_1_context_instruction_op[4]",
        )
        classification = classify_endpoint_modules(endpoints, registers)
        self.assertEqual(classification["mapped_sequential_endpoints"], 2)
        self.assertEqual(
            classification["module_counts"],
            {"backend": 1, "middleend": 1, "top": 1},
        )

    def test_electrical_gate_rejects_any_cap_or_slew_violation(self):
        clean = "max capacitance\nPin Limit Cap Slack\n"
        cap_bad = "Pin Limit Cap Slack\nnet 60 80 -20 (VIOLATED)\n"
        slew_bad = "Pin Limit Slew Slack\nnet 0.2 0.5 -0.3 (VIOLATED)\n"

        self.assertEqual(count_violations(clean), 0)
        require_electrically_clean(clean, clean)
        self.assertEqual(count_violations(cap_bad), 1)
        with self.assertRaises(ValueError):
            require_electrically_clean(cap_bad, clean)
        with self.assertRaises(ValueError):
            require_electrically_clean(clean, slew_bad)

    def test_ram_edge_report_preserves_falling_launch_and_rising_capture(self):
        report = """Startpoint: ram/dout[0]
clock core_clock (fall edge)
Endpoint: output_reg/D
clock core_clock (rise edge)

Startpoint: ram/dout[1]
clock core_clock (fall edge)
Endpoint: output_reg_1/D
clock core_clock (rise edge)
"""
        paths = parse_ram_edge_paths(report)
        self.assertEqual(len(paths), 2)
        self.assertTrue(all(path["launch_edge"] == "fall" for path in paths))
        self.assertTrue(all(path["capture_edge"] == "rise" for path in paths))

    def test_path_reports_are_skipped_until_electrical_checks_are_clean(self):
        with tempfile.TemporaryDirectory() as temp:
            target_dir = Path(temp)
            (target_dir / "max-capacitance.rpt").write_text("Pin Limit Cap Slack\n")
            (target_dir / "max-slew.rpt").write_text(
                "Pin Limit Slew Slack\nnet 0.2 0.5 -0.3 (VIOLATED)\n"
            )
            summary = summarize_target(target_dir, 1.0)

        self.assertFalse(summary["electrical_gate_pass"])
        self.assertNotIn("wns_ns", summary)
        self.assertIn("skipped", summary["path_analysis"])

    def test_summary_names_the_worst_mapped_register(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp)
            (target / "max-capacitance.rpt").write_text("Pin Limit Cap Slack\n")
            (target / "max-slew.rpt").write_text("Pin Limit Slew Slack\n")
            (target / "wns.rpt").write_text("-0.500000\n")
            (target / "tns.rpt").write_text("-1.000000\n")
            (target / "violating-endpoints.rpt").write_text(
                "Endpoint Delay Delay Slack\n"
                "--------------------------------\n"
                "_42_/D 0.970000 1.470000 -0.500000 (VIOLATED)\n"
            )
            (target / "ZirconCore-repaired.v").write_text(
                "module ZirconCore;\n"
                "  DFF_X1 _42_ (.CK(clock), .D(next), .Q(\\middleend.readyBoard.fpSpec_4 [2]));\n"
                "endmodule\n"
            )

            summary = summarize_target(target, 1.0)

        self.assertEqual(
            summary["worst_endpoint"]["mapped_register"],
            "middleend.readyBoard.fpSpec_4[2]",
        )

    def test_repair_tables_are_recorded_per_resizer_stage(self):
        log = """RESIZER_STAGE repair_design
Iteration | Area | Resized | Buffers | Nets repaired | Remaining
        0 | +0.0% | 2 | 3 | 4 | 5
RESIZER_STAGE repair_timing_setup
Iteration | Area | Resized | Buffers | Nets repaired | Remaining
        0 | +0.0% | 6 | 7 | 8 | 9
"""
        self.assertEqual(
            parse_repair_counts(log),
            {
                "repair_design": {
                    "iteration": 0,
                    "resized_cells": 2,
                    "inserted_buffers": 3,
                    "repaired_nets": 4,
                    "remaining": 5,
                },
                "repair_timing_setup": {
                    "iteration": 0,
                    "resized_cells": 6,
                    "inserted_buffers": 7,
                    "repaired_nets": 8,
                    "remaining": 9,
                },
            },
        )

    def test_repair_tables_prefer_final_stage_summary(self):
        log = """RESIZER_STAGE repair_design
Iteration | Area | Resized | Buffers | Nets repaired | Remaining
        0 | +0.0% | 2 | 3 | 4 | 5
    final | +4.2% | 17 | 54 | 40 | 0
"""
        self.assertEqual(
            parse_repair_counts(log),
            {
                "repair_design": {
                    "iteration": "final",
                    "resized_cells": 17,
                    "inserted_buffers": 54,
                    "repaired_nets": 40,
                    "remaining": 0,
                },
            },
        )

    def test_clean_electrical_gate_parses_every_violating_endpoint(self):
        with tempfile.TemporaryDirectory() as temp:
            target_dir = Path(temp)
            (target_dir / "max-capacitance.rpt").write_text("No violations\n")
            (target_dir / "max-slew.rpt").write_text("No violations\n")
            (target_dir / "wns.rpt").write_text("worst slack max -0.125\n")
            (target_dir / "tns.rpt").write_text("tns max -0.150\n")
            (target_dir / "openroad.log").write_text(
                "RESIZER_SETUP_REPAIR 0\nRESIZER_SLEW_MARGIN 50\n"
            )
            (target_dir / "violating-endpoints.rpt").write_text(
                "Startpoint                           Endpoint                                  Slack\n"
                "-----------------------------------------------------------------------------------\n"
                "_a_/Q (DFF_X1)                       _b_/D (DFF_X1)                          -0.12500\n"
                "_c_/Q (DFF_X1)                       _d_/D (DFF_X1)                          -0.02500\n"
            )
            summary = summarize_target(target_dir, 1.0)

        self.assertTrue(summary["electrical_gate_pass"])
        self.assertEqual(summary["wns_ns"], -0.125)
        self.assertEqual(summary["tns_ns"], -0.15)
        self.assertEqual(summary["violating_endpoint_count"], 2)
        self.assertEqual(summary["worst_endpoint"]["endpoint"], "_b_/D")
        self.assertFalse(summary["setup_repair_performed"])
        self.assertEqual(summary["slew_repair_margin_percent"], 50)
        self.assertEqual(
            summary["timing_reference"],
            "after electrical repair, before setup repair",
        )


if __name__ == "__main__":
    unittest.main()
