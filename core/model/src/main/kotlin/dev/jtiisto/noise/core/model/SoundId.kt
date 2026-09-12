package dev.jtiisto.noise.core.model

enum class SoundCategory(val title: String) {
    NOISE("Noise"),
    NATURE("Nature"),
    AMBIENCE("Ambience"),
}

/**
 * The catalog. Order here is display order within each category. [hue] is
 * the accent hue (degrees, 0..360) the UI tints tiles and the background
 * with; [blurb] is the one-line description shown on long-press.
 */
enum class SoundId(
    val category: SoundCategory,
    val displayName: String,
    val hue: Float,
    val blurb: String,
) {
    WHITE(SoundCategory.NOISE, "White noise", 215f, "Full-spectrum hiss. Masks everything equally."),
    PINK(SoundCategory.NOISE, "Pink noise", 335f, "Softer than white — energy falls 3 dB per octave."),
    BROWN(SoundCategory.NOISE, "Brown noise", 25f, "Deep and rumbly, like a distant waterfall."),
    BLUE(SoundCategory.NOISE, "Blue noise", 200f, "Bright and airy; rises 3 dB per octave."),
    VIOLET(SoundCategory.NOISE, "Violet noise", 270f, "Very bright; often used for tinnitus masking."),
    GREY(SoundCategory.NOISE, "Grey noise", 0f, "Shaped to sound flat to the ear at every pitch."),

    RAIN(SoundCategory.NATURE, "Rain", 205f, "Steady rain on leaves with soft, distant body."),
    HEAVY_RAIN(SoundCategory.NATURE, "Downpour", 220f, "Dense, bright rain sheets and a full low end."),
    THUNDERSTORM(SoundCategory.NATURE, "Thunderstorm", 250f, "A downpour with gentle, distant thunder rolls."),
    OCEAN(SoundCategory.NATURE, "Ocean", 190f, "Slow swells breaking on a wide beach."),
    WIND(SoundCategory.NATURE, "Wind", 170f, "Gusts moving through an open field."),
    CAMPFIRE(SoundCategory.NATURE, "Campfire", 30f, "Low glow with crackles and the odd pop."),
    STREAM(SoundCategory.NATURE, "Stream", 160f, "A babbling brook over stones."),
    CRICKETS(SoundCategory.NATURE, "Crickets", 95f, "A warm summer night, far from the road."),

    FAN(SoundCategory.AMBIENCE, "Fan", 45f, "A box fan on medium, blades turning all night."),
    AIRPLANE(SoundCategory.AMBIENCE, "Airplane cabin", 230f, "Cruising altitude hum with a whisper of vent air."),
    ;

    /** Stable key for persistence (never rename enum constants without a migration). */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): SoundId? = entries.firstOrNull { it.key == key }
        fun inCategory(category: SoundCategory): List<SoundId> = entries.filter { it.category == category }
    }
}
