package io.github.clobrano.greenwave.model

/** What was observed at a traffic light at a given instant. */
enum class ObservationKind {
    /** The light just turned green ("GREEN NOW" button or GPS restart). */
    GREEN_START,

    /** The light just turned amber/red ("RED NOW" button). */
    RED_START,

    /** It was green at that instant (e.g. passing without stopping). */
    GREEN_SEEN,

    /** It was red at that instant (e.g. arriving and stopping). */
    RED_SEEN,
}

/** An observation; [time] is in seconds on the same scale as [SignalPlan]. */
data class Observation(val time: Double, val kind: ObservationKind)
