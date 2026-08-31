package zircon

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zircon.backend.UopRef

class UopFootprintSpec extends AnyFlatSpec with Matchers {
  behavior of "compact UopRef"

  it should "cut the frozen Zircon-2024 IQ payload by at least thirty percent" in {
    val oldIqPayloadAndStateBits = 6924
    val newUopBits = (new UopRef).getWidth
    val newIqPayloadBits = newUopBits * (12 + 4 + 8)

    newUopBits shouldBe 86
    newIqPayloadBits shouldBe 2064
    newIqPayloadBits * 10 should be <= (oldIqPayloadAndStateBits * 7)
  }
}
