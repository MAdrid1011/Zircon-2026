# Provenance and licensing

This directory does not vendor the XiangShan, Chipyard, BOOM, or EEMBC CoreMark
repositories. `scripts/fetch.sh` obtains pinned upstream revisions into the ignored
`work/` directory, where their original licenses remain authoritative.

| Component | Upstream | Revision source | License source |
| --- | --- | --- | --- |
| Zircon CoreMark sources | `RV-Software` submodule | Parent repository gitlink | `RV-Software/coremark/LICENSE.md` |
| XiangShan Yanqihu | OpenXiangShan/XiangShan | `configs/revisions.lock` | Upstream `LICENSE` (Mulan PSL v2) |
| BOOM / Chipyard | ucb-bar/chipyard and riscv-boom | `configs/revisions.lock` | Upstream `LICENSE*` files |
| Mill launcher | com-lihaoyi/mill | Pinned URL and SHA-256 | Upstream Apache-2.0 license |

The local RV64 files are a platform port: startup, linker layout, console/exit
transport, and cycle timing. The five CoreMark algorithm sources are compiled
directly from the pinned `RV-Software` submodule and are not duplicated here.
