# ADR-0034: Balanced FirstFault selection tree

状态：Accepted

## 背景

The fixed-device timing reports showed the IntIQ wakeup and FirstFault record
write-enable paths re-converging through a linear candidate fold. Each of the
six candidates contributed a full fault payload mux and an age comparator in
series.

## 决策

`FirstFaultTracker` now reduces candidate validity, age, and payload through a
balanced pairwise tree. Pairwise selection keeps the left candidate on equal
age, matching the former linear fold. The retained record is compared with the
tree result only once; flush, squash, clear, and precise fault metadata
semantics are unchanged.

## 验证

`FirstFaultTrackerSpec` 2/2 and `IntegerDispatchRecoveryBackendSpec` 3/3 pass;
compile and platform RTL generation must pass before the next fixed-device run.
