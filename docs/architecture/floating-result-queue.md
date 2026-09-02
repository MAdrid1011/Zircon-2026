# Floating-point result queue

`FloatingResultQueue` is the four-entry M4 staging point between variable-
latency F execution and architectural commit. It does not use completion FIFO
order: each entry retains its ROB tag, and `commit` is valid only when an entry
matches `commitTag`. A younger F result may therefore arrive first without
allowing it to write an FPR or update `fflags` early.

## Interface and state

Each entry is `{robTag, writesFloat, fprAddress, fprData, flags}`. One E2
result may enqueue each cycle while a registered free slot exists. The commit
consumer supplies the current ROB head tag and accepts the matching result;
that fire is the only future source for `FloatingRegisterFile.write`,
`FloatingScoreboard.complete`, and `MachineCSRFile.fpCommit`.

Selective squash removes only tags younger than its boundary. Global flush
removes every uncommitted entry. Neither recovery path permits enqueue or
commit transfer. Duplicate ROB tags, out-of-range tags, and occupancy beyond
four entries assert immediately.

This queue currently owns only FPR/flag architectural state. F operations
whose final result targets a GPR will use the ordinary completion path when
the F decoder and E2 execution integration exist.

## Verification mapping

`FloatingResultQueueSpec` enqueues younger-before-older results, holds the
older matching result under commit backpressure, commits by exact ROB tag, and
proves selective squash/global flush remove only the correct retained entries.
Integration remains responsible for FPU exception flags, commit trace fields,
scoreboard release, and true F instruction execution.
