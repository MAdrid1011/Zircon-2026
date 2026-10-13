package ZirconConfig

/** Static machine properties selected before elaboration. */
sealed trait CoreProfile {
    def name: String
    def privileged: Boolean
    def axiDataBits: Int
    def dcacheSets: Int
    def l2: L2CacheParams
    def vlen: Int
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
        val l2 = L2CacheParams(sets = 256)
        val vlen = 256
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
    computeEntries: Int = 12,
    memoryEntries: Int = 8,
) {
    require(vlen == 256 && elen == 32 && lanes == 8)
    require(physicalRegisters == 64)
}
