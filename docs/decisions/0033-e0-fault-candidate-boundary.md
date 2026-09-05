# ADR-0033: Registered E0 fault candidate boundary

状态：Accepted

## 背景

The fixed-device report for `d331c5b` still showed a long path from the IntIQ
`entrySourceReady` state through E0 execution fault generation and the
FirstFault record write-enable network. The first production register on the
fault candidate was not sufficient to isolate the wide FirstFault age/payload
selection cone.

## 决策

In the production registered-wakeup configuration, capture the E0 fault
candidate in a second register before presenting it to `FirstFaultTracker`.
Dispatch/decode faults remain same-cycle candidates. An E0 faulting uop has no
successful completion and therefore cannot retire while this candidate is in
flight; the extra cycle preserves precise EPC/cause/tval ordering. Flush and
selective squash suppress the captured candidate as before.

## 验证

Run the integer backend, recovery, CoreShell fault smoke, compile, and platform
RTL generation before the next `xc7a200tfbg676-2L` implementation.
