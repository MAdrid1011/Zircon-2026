# Strategy Review

The current problem is a same-PC, multi-target JALR. A larger set-associative BTB does not solve that pattern because one static BTB entry still represents one recent target. The established solution is a separate history-correlated indirect target predictor with the BTB retained as a base predictor.

For conditional direction prediction, TAGE-SC-L remains the strongest practical reference family: TAGE supplies geometric history tables, a statistical corrector handles weak/bias cases, and a loop predictor handles regular loops. Zircon already follows this shape at smaller scale, so Issue #53 does not replace its direction predictor.

For indirect targets, ITTAGE is the direct architectural fit. Seznec's TAGE work explicitly adapts tagged geometric histories to indirect targets, and the 64 KiB ITTAGE design scales the idea to 16 components. Zircon uses a compact four-table version because it must fit the existing IF1/IF2 boundary and because CoreMark's dominant hotspot is resolved by short histories.

Bit-Level Perceptron Prediction (BLBP, ISCA 2019) is a stronger academic alternative at equivalent reported budget: its paper reports 0.183 indirect mispredictions per thousand instructions versus 0.193 for ITTAGE on its SPEC/mobile suite. It predicts target bits using perceptron correlations. That result does not make BLBP the right first implementation here: it has more arithmetic and timing complexity, while compact ITTAGE already reaches 99.129% on the target workload without adding a frontend stage.

References:

- Andre Seznec and Pierre Michaud, [A case for (partially) tagged geometric history length branch prediction](https://inria.hal.science/hal-03408381/), JILP 2006.
- Andre Seznec, [A 64-Kbytes ITTAGE indirect branch predictor](https://inria.hal.science/hal-00639041/), JWAC-2 2011.
- Andre Seznec, [TAGE-SC-L branch predictors](https://inria.hal.science/hal-01086920/), CBP-4/JILP 2014.
- Elba Garza et al., [Bit-level perceptron prediction for indirect branches](https://doi.org/10.1145/3307650.3322217), ISCA 2019.
