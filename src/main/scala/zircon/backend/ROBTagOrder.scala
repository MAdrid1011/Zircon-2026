package zircon.backend

import chisel3._
import zircon.ZirconCoreConfig

/** ROB age comparisons for the single live modulo-N instruction window.
  *
  * Generation bits reject stale messages; live-entry ordering is determined by
  * the physical index distance from the current ROB head because occupancy can
  * never exceed one traversal of the ROB.
  */
object ROBTagOrder {
  def ageFromHead(
      tag: UInt,
      robHeadTag: UInt,
      config: ZirconCoreConfig
  ): UInt = {
    val headIndex = robHeadTag(config.robIndexWidth - 1, 0)
    val index = tag(config.robIndexWidth - 1, 0)
    Mux(index >= headIndex,
      index - headIndex,
      index + config.robEntries.U - headIndex)
  }

  def isYounger(
      candidateTag: UInt,
      boundaryTag: UInt,
      robHeadTag: UInt,
      config: ZirconCoreConfig
  ): Bool = ageFromHead(candidateTag, robHeadTag, config) >
    ageFromHead(boundaryTag, robHeadTag, config)
}
