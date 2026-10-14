import chisel3._

object VectorTestUtils extends chisel3.simulator.PeekPokeAPI {
    def clear(data: Data): Unit = data match {
        case value: Bool => value.poke(false)
        case value: UInt => value.poke(0)
        case value: SInt => value.poke(0)
        case record: Record => record.elements.values.foreach(clear)
        case values: Vec[_] => values.foreach(clear)
        case other => throw new IllegalArgumentException(s"Unsupported test field $other")
    }
}
