# Commit implementation

Owns ROB, FTQ, SQ, committed store buffering and architectural CSR state under
[Commit](../../../../docs/Commit.md). `CommitIO` groups frontend training and
recovery, middleend ordered allocation/retirement, backend completion and CSR
authorization, maintenance and system interrupt inputs. Vector retirement is
present only in the nonprivileged performance configuration.

Recovery selects a ROB-head instruction and captures the recovery target and
payload at the existing delayed recovery boundary. The following cycle sends
the clear/redirect; rename restoration follows that clear. Existing retirement,
trap delegation, privilege changes and store ownership priorities remain in the
source. Interrupt vector bases and the cause offset add through `BLevelPAdder32`
before the original target register; modulo-32-bit addresses and direct/vectored
mode selection are unchanged. No register or cycle is added.

ASIC mapping preserves the grouped adder structure. Verification covers recovery
priority, CSR trap delegation and mode selection, modulo-32-bit target arithmetic,
and privileged-system execution. Standard-cell and SRAM/physical timing are
separate verification obligations.
