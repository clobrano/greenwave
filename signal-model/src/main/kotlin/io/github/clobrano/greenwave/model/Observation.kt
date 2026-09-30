package io.github.clobrano.greenwave.model

/** Cosa è stato osservato a un semaforo in un certo istante. */
enum class ObservationKind {
    /** Il semaforo è appena diventato verde (tasto "VERDE ORA" o ripartenza da GPS). */
    GREEN_START,

    /** Il semaforo è appena diventato giallo/rosso (tasto "ROSSO ORA"). */
    RED_START,

    /** In quell'istante era verde (ad esempio passaggio senza fermarsi). */
    GREEN_SEEN,

    /** In quell'istante era rosso (ad esempio arrivo e fermata). */
    RED_SEEN,
}

/** Un'osservazione; [time] è in secondi sulla stessa scala di [SignalPlan]. */
data class Observation(val time: Double, val kind: ObservationKind)
