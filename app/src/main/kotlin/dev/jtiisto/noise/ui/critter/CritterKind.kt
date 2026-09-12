package dev.jtiisto.noise.ui.critter

import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundCategory
import dev.jtiisto.noise.core.model.SoundId

/**
 * The little animal that keeps the orb company on the Home screen.
 *
 * One critter per nature sound, plus a sleeping cat for everything else — it
 * suits "Hush" and fills the quiet of a mix that is only noise, only ambience,
 * or empty.
 */
enum class CritterKind {
    /** Default: curled up asleep with a tiny floating "z". */
    CAT,

    /** Rain, Downpour, Thunderstorm. */
    FROG,

    /** Ocean — with a little spout. */
    WHALE,

    /** Wind — a round puffball bird. */
    BIRD,

    /** Campfire — curled and cosy. */
    FOX,

    /** Stream. */
    DUCK,

    /** Crickets — a soft, slow glowing blink. */
    FIREFLY,
}

/**
 * The critter a [mix] should show.
 *
 * The lead is the **first** sound in display (mix) order whose category is
 * [SoundCategory.NATURE]; its animal wins. A mix with no nature sound — noise
 * only, ambience only, or empty — gets the sleeping [CritterKind.CAT].
 *
 * Pure: no Compose, no Android, so it is unit tested straight on the JVM.
 */
fun critterFor(mix: Mix): CritterKind {
    val lead = mix.layers.firstOrNull { it.id.category == SoundCategory.NATURE }?.id
        ?: return CritterKind.CAT
    return when (lead) {
        SoundId.RAIN, SoundId.HEAVY_RAIN, SoundId.THUNDERSTORM -> CritterKind.FROG
        SoundId.OCEAN -> CritterKind.WHALE
        SoundId.WIND -> CritterKind.BIRD
        SoundId.CAMPFIRE -> CritterKind.FOX
        SoundId.STREAM -> CritterKind.DUCK
        SoundId.CRICKETS -> CritterKind.FIREFLY
        // Every current nature sound is mapped above; a nature sound added
        // later falls back to the cat until it earns its own critter.
        else -> CritterKind.CAT
    }
}
