package ZirconConfig

/** Shape of the shared WB-to-EX/EX1 bypass network. */
case class BypassParams(
    backend: BackendParams = BackendParams(),
    numProducers: Int = 6,
    consumerSources: Seq[Int] = Seq(2, 2, 2, 3),
    consumerProducers: Seq[Seq[Seq[Int]]] = Seq.empty,
    captureConsumers: Set[Int] = Set.empty,
    guaranteedCaptureProducers: Set[Int] = Set.empty,
) {
    require(numProducers > 0)
    require(consumerSources.nonEmpty && consumerSources.forall(_ > 0))

    val producerSets: Seq[Seq[Seq[Int]]] = if (consumerProducers.nonEmpty) {
        consumerProducers
    } else {
        consumerSources.map(sources => Seq.fill(sources)(0 until numProducers))
    }

    require(producerSets.size == consumerSources.size)
    require(producerSets.zip(consumerSources).forall { case (sets, sources) => sets.size == sources })
    require(producerSets.flatten.forall(set => set.nonEmpty && set.distinct == set &&
        set.forall(producer => producer >= 0 && producer < numProducers)))
    require(captureConsumers.forall(index => index >= 0 && index < consumerSources.size))
    require(guaranteedCaptureProducers.forall(index => index >= 0 && index < numProducers))
}

object BypassParams {
    /** Backend topology: Mix source 2 is FP-only and resolved Loads are read from the PRF. */
    def backend(backend: BackendParams = BackendParams()): BypassParams = BypassParams(
        backend = backend,
        consumerSources = Seq(2, 2, 2, 3),
        consumerProducers = Seq(
            Seq.fill(2)(Seq(0, 1, 2, 3, 4, 5)),
            Seq.fill(2)(Seq(0, 1, 2, 3, 4, 5)),
            Seq.fill(2)(Seq(0, 1, 2, 3, 4, 5)),
            Seq(Seq(0, 1, 2, 3), Seq(0, 1, 2, 3), Seq(3)),
        ),
    )

    /** Backend topology for two integer arithmetic pipes. */
    def twoArithBackend(backend: BackendParams = BackendParams()): BypassParams = BypassParams(
        backend = backend,
        numProducers = 5,
        consumerSources = Seq(2, 2, 3),
        consumerProducers = Seq(
            Seq.fill(2)(Seq(0, 1, 2, 3, 4)),
            Seq.fill(2)(Seq(0, 1, 2, 3, 4)),
            Seq(Seq(0, 1, 2), Seq(0, 1, 2), Seq(2)),
        ),
        captureConsumers = Set(2),
        guaranteedCaptureProducers = Set(0, 1),
    )
}
