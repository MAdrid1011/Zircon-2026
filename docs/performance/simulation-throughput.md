# ZirconSim Throughput Record

This is a deterministic simulator throughput measurement, not the frozen IPC
release comparison. The run uses the current trace-enabled RTL, seed `1`, the
default deterministic AXI schedule, and an explicit 100,000-cycle limit.

```text
make -C ZirconSim throughput THROUGHPUT_CYCLES=100000
status=timeout  cycles=100000  seed=1  retired=133119
```

The measured retirement rate is `1.33119` instructions/cycle. The timeout is
the requested measurement boundary (`--allow-timeout`); no `tohost` completion
or architectural pass is claimed by this target. Full workload IPC and the
Zircon-2024 comparison remain M5 release work.
