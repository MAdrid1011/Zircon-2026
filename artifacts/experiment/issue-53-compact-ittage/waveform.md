# Short-Waveform Analysis

## Capture

The updated RTL was captured over CoreMark cycles 120000 through 121999 with seed 1. The generated `build/zircon.vcd` is 51,003,402 bytes and has SHA-256 `eb63e1f31f3de7dc0b38081f3f346d93f4df37fde5e71124e847b0d6c8882d81`.

## Hotspot result

`PC=0x80000820` is the `jr s0` jump-table dispatch in `core_state_transition`. Sampling the two branch execution pipelines once per simulated clock cycle produced 94 events: 93 correct and one incorrect, or 98.936% accuracy. The baseline issue recorded 17 correct out of 55 events, or 30.909%, in the same cycle window.

The observed updated targets were `0x80000824`, `0x80000844`, `0x8000085c`, `0x80000878`, `0x80000888`, `0x800008a4`, and `0x800008b8`. Correct predictions therefore cover multiple targets for one static JALR rather than collapsing to a last-target result.

At the prediction metadata boundary, provider use in the window was concentrated in table 1 (80 events, 79 correct) and table 2 (14 events, 14 correct). The only failure was at cycle 121959: the weak table-1 provider held the correct target `0x800008b8`, but its zero confidence selected alternate/base target `0x80000824`. This is consistent with the intended cold/weak-provider policy.

## Representative corrected events

| Cycle | Predicted target | Actual target | Result |
| ---: | --- | --- | --- |
| 120080 | `0x8000085c` | `0x8000085c` | correct |
| 120090 | `0x800008a4` | `0x800008a4` | correct |
| 120104 | `0x800008b8` | `0x800008b8` | correct |
| 120657 | `0x80000824` | `0x80000824` | correct |
| 121047 | `0x80000844` | `0x80000844` | correct |
| 121452 | `0x80000878` | `0x80000878` | correct |

The analysis samples the VCD's real top-level clock (`*f"`) rather than treating both half-cycle dumps as independent events.
