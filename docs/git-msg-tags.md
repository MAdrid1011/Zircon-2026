# Git Message Tags

Use `[tag] concise imperative summary` for commits. For a planned GitHub issue
with a concrete implementation plan, prefix the same tag with `[plan]`, such as
`[plan][eda]: Close Nangate45 timing at 1 ns`.

| Tag | Scope | Example |
| --- | --- | --- |
| `eda` | Synthesis, timing analysis, and physical-design flows | `[eda] Add Nangate45 placement repair` |
| `rtl` | Chisel and RTL implementation changes | `[rtl] Balance issue selection logic` |
| `verification` | Tests, simulation, and equivalence checking | `[verification] Check divider selector equivalence` |
| `docs` | Documentation-only changes | `[docs] Document Nangate45 timing flow` |
| `chore` | Build, tooling, and repository maintenance | `[chore] Pin Nangate45 physical collateral` |
