package dev.tapio.hush.core.model

/** One sound in the mix with its slider position (0..1, linear UI value). */
data class SoundLayer(val id: SoundId, val gain: Float = DEFAULT_GAIN) {
    init {
        require(gain in 0f..1f) { "gain must be within 0..1, was $gain" }
    }

    companion object {
        const val DEFAULT_GAIN = 0.7f
    }
}

/**
 * An ordered set of layers (a sound appears at most once). Insertion order is
 * display order. Bounded by [MAX_LAYERS]; use [with] / [without] / [gain]
 * rather than constructing lists by hand so the invariants hold.
 */
data class Mix(val layers: List<SoundLayer> = emptyList()) {
    init {
        require(layers.size <= MAX_LAYERS) { "at most $MAX_LAYERS layers, got ${layers.size}" }
        require(layers.map { it.id }.distinct().size == layers.size) { "duplicate layers in $layers" }
    }

    val isEmpty: Boolean get() = layers.isEmpty()
    val isFull: Boolean get() = layers.size >= MAX_LAYERS
    val ids: List<SoundId> get() = layers.map { it.id }

    fun contains(id: SoundId): Boolean = layers.any { it.id == id }
    fun layer(id: SoundId): SoundLayer? = layers.firstOrNull { it.id == id }

    /** Returns null when the mix is full. */
    fun with(id: SoundId, gain: Float = SoundLayer.DEFAULT_GAIN): Mix? = when {
        contains(id) -> this
        isFull -> null
        else -> Mix(layers + SoundLayer(id, gain))
    }

    fun without(id: SoundId): Mix = Mix(layers.filterNot { it.id == id })

    fun gain(id: SoundId, gain: Float): Mix =
        Mix(layers.map { if (it.id == id) it.copy(gain = gain.coerceIn(0f, 1f)) else it })

    /** "Rain + Brown noise", or [emptyTitle] for an empty mix. */
    fun title(emptyTitle: String = "Nothing playing"): String =
        if (isEmpty) emptyTitle else layers.joinToString(" + ") { it.id.displayName }

    companion object {
        const val MAX_LAYERS = 3
        val EMPTY = Mix()

        fun of(vararg layers: Pair<SoundId, Float>): Mix = Mix(layers.map { SoundLayer(it.first, it.second) })
    }
}
