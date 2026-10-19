# Vector store buffer verification

`VectorStoreBufferSpec` checks the side buffer's collection, retirement, drain,
cache request/response, and flush contracts from [Memory](../../../../../docs/Memory.md).
The scattered-byte scoreboard checks EEW 8/16/32, disabled elements, misaligned
cross-line fragments and the 32-bit address wrap, under request backpressure
and delayed responses. Ordered segmented stores verify element-before-field
ordering for overlapping addresses. Contiguous cases cover masks, retained
committed ownership through flush, and completion only after the final response.

The expected byte addresses use architectural 32-bit wrap. Data and address are
checked together only on accepted cache requests; a run must drain completely.
Use Verilator through `testOnly VectorStoreBufferSpec VectorStoreCoalesceSpec`.
