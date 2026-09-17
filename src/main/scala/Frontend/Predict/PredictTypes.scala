import chisel3._
import chisel3.util._
import ZirconConfig.FrontendParams

class FrontendIndirectMeta(p: FrontendParams) extends Bundle {
    val indices = Vec(p.ittageCount, UInt(p.ittageIndexBits.W))
    val tags = Vec(p.ittageCount, UInt(p.ittageTagBits.W))
    // Zero selects the Main BTB; values 1..ittageCount identify tagged providers.
    val providers = Vec(p.fetchWidth, UInt(p.ittageProviderBits.W))
    val providerTargets = Vec(p.fetchWidth, UInt(32.W))
    val providerConfidence = Vec(p.fetchWidth, UInt(2.W))
    val alternateValid = UInt(p.fetchWidth.W)
    val alternateTargets = Vec(p.fetchWidth, UInt(32.W))
    val predictedTargets = Vec(p.fetchWidth, UInt(32.W))
    val aheadValid = Bool()
}

class FrontendDirectionMeta(p: FrontendParams) extends Bundle {
    // Lookup-time keys are retained for delayed commit training.
    val phtIndex = UInt(p.phtIndexBits.W)
    val tageIndices = Vec(p.tageCount, UInt(p.tageIndexBits.W))
    val tageTags = Vec(p.tageCount, UInt(p.tageTagBits.W))
    // Zero selects PHT; values 1..tageCount select a tagged table.
    val tageProviders = Vec(p.fetchWidth, UInt(p.providerBits.W))
    val alternateDirections = UInt(p.fetchWidth.W)
    val aheadValid = Bool()
    val tcBiasIndex = UInt(p.tcIndexBits.W)
    val tcBiasTag = UInt(10.W)
    val tcHistoryIndex = UInt(p.tcIndexBits.W)
    val tcHistoryTag = UInt(8.W)
    val ittage = new FrontendIndirectMeta(p)
}

class FrontendTrainingMeta(p: FrontendParams) extends Bundle {
    val tageIndices = Vec(p.tageCount, UInt(p.tageIndexBits.W))
    val tageTags = Vec(p.tageCount, UInt(p.tageTagBits.W))
    val tageProviders = Vec(p.fetchWidth, UInt(p.providerBits.W))
    val alternateDirections = UInt(p.fetchWidth.W)
    val aheadValid = Bool()
    val tcHistoryIndex = UInt(p.tcIndexBits.W)
    val tcHistoryTag = UInt(8.W)
    val ittage = new FrontendIndirectMeta(p)
}

class FrontendTrainingRecord(p: FrontendParams) extends Bundle {
    val pc = UInt(32.W)
    val mask = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val taken = UInt(p.fetchWidth.W)
    val meta = new FrontendTrainingMeta(p)
    val earlyDirections = UInt(p.fetchWidth.W)
    val biasDirections = UInt(p.fetchWidth.W)
}

class FrontendTraining(p: FrontendParams) extends FrontendTrainingRecord(p) {
    val targets = Vec(p.fetchWidth, UInt(32.W))
}
