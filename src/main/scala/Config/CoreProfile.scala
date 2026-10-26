package ZirconConfig

/** Static machine properties selected before elaboration. */
sealed trait CoreProfile {
    def name: String
    def privileged: Boolean
    def axiDataBits: Int
    def dcacheSets: Int
    def l2: L2CacheParams
    def vlen: Int
    def vector: VectorParams = VectorParams()
    def frontend(observe: Boolean): FrontendParams = FrontendParams(observe = observe)
    final def icache: ICacheParams = ICacheParams(tlbEnabled = privileged, directPhysical = !privileged)
}

object CoreProfile {
    case object System extends CoreProfile {
        val name = "system"
        val privileged = true
        val axiDataBits = 64
        val dcacheSets = 16
        val l2 = L2CacheParams()
        val vlen = 0
    }
    case object Performance extends CoreProfile {
        val name = "performance"
        val privileged = false
        val axiDataBits = 256
        val dcacheSets = 128
        val l2 = L2CacheParams(sets = 128)
        val vlen = 256
        override def vector: VectorParams = VectorParams(computeEntries = 8, memoryEntries = 8)
        override def frontend(observe: Boolean): FrontendParams = FrontendParams(
            historyLengths = Seq(4, 8, 16, 32, 64), rasDepth = 8, observe = observe)
    }
    def named(name: String): CoreProfile = name match {
        case "system" => System
        case "performance" => Performance
        case _ => throw new IllegalArgumentException(s"Unknown core profile: $name")
    }
}

case class VectorParams(
    vlen: Int = 256,
    elen: Int = 32,
    lanes: Int = 8,
    physicalRegisters: Int = 64,
    computeEntries: Int = 6,
    memoryEntries: Int = 4,
) {
    require(vlen == 256 && elen == 32 && lanes == 8)
    require(physicalRegisters >= 56 && physicalRegisters <= 64 && physicalRegisters % 8 == 0)
    require(computeEntries >= 2 && memoryEntries >= 2)
}
