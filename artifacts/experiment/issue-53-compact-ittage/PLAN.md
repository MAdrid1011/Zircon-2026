# Compact ITTAGE Experiment Plan

## Selected idea

Add a compact ITTAGE-like indirect target predictor beside the Main BTB. The Main BTB remains the base predictor, while the new predictor may override only ordinary indirect jumps in IF2; returns retain RAS priority.

## Requirements and constraints

- Preserve Spike differential correctness.
- Keep the CoreMark baseline command, ELF, seed, and metric definitions unchanged.
- Do not modify conditional direction prediction, direct jumps, calls, or returns.
- Train from commit using lookup-time indices and tags retained through the FTQ.
- Keep the IF1/IF2 frontend boundary and avoid adding a fetch stage.
- Compare against commit `4cee6f541aa088d9439c14d07e43144449161736`:
  - cycles: 371,523
  - instructions: 374,266
  - IPC: 1.007383
  - conditional accuracy: 96.163%
  - ordinary indirect accuracy: 40.571% (1,863 / 4,592)

## Run contract

- Run id: `issue-53-compact-ittage`
- Tier: main/test
- Research question: Can a small history-indexed tagged target predictor resolve CoreMark's multi-target JALR without regressing correctness or other CFI classes?
- Null hypothesis: A compact ITTAGE does not materially improve ordinary indirect accuracy or IPC.
- Alternative hypothesis: A compact ITTAGE raises ordinary indirect accuracy to at least 75% while preserving conditional and return accuracy.
- Initial configuration: 4 tables, 128 rows/table, history lengths 4/8/16/32, 9-bit tags, 2-bit confidence, 1 useful bit, 30-bit RV32 target.
- Primary metrics: ordinary indirect correct/total/accuracy, cycles, IPC.
- Secondary metrics: conditional accuracy, call accuracy, return accuracy, Spike differential status.
- Stop condition: complete unit tests and one comparable CoreMark seed=1 run, or stop early on an architectural correctness failure that cannot be isolated with a bounded test.
- Abandonment condition: no measurable indirect improvement after one correct implementation and one evidence-driven parameter/history revision.

## Code change map

- `src/main/scala/Config/FrontendParams.scala`: compact ITTAGE parameters.
- `src/main/scala/Frontend/Predict/PredictTypes.scala`: lookup-time and training metadata.
- `src/main/scala/Frontend/Predict/IndirectTargetPredictor.scala`: predictor tables, selection, update, allocation.
- `src/main/scala/Frontend/Predict/Predict.scala`: IF1 query, IF2 override, commit training.
- `src/main/scala/Frontend/Frontend.scala`: carry finalized IF2 metadata to PD/FTQ.
- `src/main/scala/Frontend/Decode/PreDecoders.scala`: retain indirect metadata for commit.
- `src/test/scala/Frontend/IndirectTargetPredictorSpec.scala`: deterministic multi-target regression.

## Execution path

1. Elaborate and run the focused predictor test.
2. Run the frontend/unit test surface affected by metadata changes.
3. Build the simulator and CoreMark.
4. Run the same CoreMark+Spike differential workload as the baseline.
5. Compare metrics and inspect a short waveform only if the aggregate result is ambiguous.

## Expected outputs

- `metrics.json` and `metrics.md`
- `summary.md`
- `run_manifest.json`
- focused test and CoreMark command/output pointers

## Revision log

- 2026-09-17: Initial contract created from Issue #53 baseline and literature-backed compact ITTAGE design.
