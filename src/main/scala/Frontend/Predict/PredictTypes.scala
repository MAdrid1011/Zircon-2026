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
    val tageConfidence = Vec(p.fetchWidth, UInt(2.W))
    val tageDirections = UInt(p.fetchWidth.W)
    val alternateDirections = UInt(p.fetchWidth.W)
    val aheadValid = Bool()
    val scBiasTag = UInt(10.W)
    val scIndices = Vec(p.scCount, UInt(p.scIndexBits.W))
    val scThresholdIndex = UInt(p.scThresholdIndexBits.W)
    val scPredictions = UInt(p.fetchWidth.W)
    val scLowMargin = UInt(p.fetchWidth.W)
    val loopIndex = UInt(p.loopIndexBits.W)
    val loopValid = UInt(p.fetchWidth.W)
    val loopPredictions = UInt(p.fetchWidth.W)
    val ittage = new FrontendIndirectMeta(p)
}

class FrontendTrainingMeta(p: FrontendParams) extends Bundle {
    val tageIndices = Vec(p.tageCount, UInt(p.tageIndexBits.W))
    val tageTags = Vec(p.tageCount, UInt(p.tageTagBits.W))
    val tageProviders = Vec(p.fetchWidth, UInt(p.providerBits.W))
    val alternateDirections = UInt(p.fetchWidth.W)
    val aheadValid = Bool()
    val scIndices = Vec(p.scCount, UInt(p.scIndexBits.W))
    val scThresholdIndex = UInt(p.scThresholdIndexBits.W)
    val scPredictions = UInt(p.fetchWidth.W)
    val scLowMargin = UInt(p.fetchWidth.W)
    val loopIndex = UInt(p.loopIndexBits.W)
    val loopValid = UInt(p.fetchWidth.W)
    val loopPredictions = UInt(p.fetchWidth.W)
    val ittage = new FrontendIndirectMeta(p)
}

class FrontendTrainingRecord(p: FrontendParams) extends Bundle {
    val pcWord = UInt(30.W)
    val mask = UInt(p.fetchWidth.W)
    val kinds = Vec(p.fetchWidth, UInt(3.W))
    val taken = UInt(p.fetchWidth.W)
    val meta = new FrontendTrainingMeta(p)
    val earlyDirections = UInt(p.fetchWidth.W)
}

class FrontendTraining(p: FrontendParams) extends FrontendTrainingRecord(p) {
    val targets = Vec(p.fetchWidth, UInt(32.W))
}
