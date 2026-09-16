package dev.tapio.hush.core.model

/** A curated one-tap mix. */
data class Scene(val id: String, val name: String, val mix: Mix)

object Scenes {
    val all: List<Scene> = listOf(
        Scene("stormy_night", "Stormy night", Mix.of(SoundId.THUNDERSTORM to 0.75f, SoundId.WIND to 0.35f)),
        Scene("cabin", "Cabin", Mix.of(SoundId.CAMPFIRE to 0.7f, SoundId.WIND to 0.3f, SoundId.RAIN to 0.35f)),
        Scene("seaside", "Seaside", Mix.of(SoundId.OCEAN to 0.8f, SoundId.WIND to 0.25f)),
        Scene("deep_focus", "Deep focus", Mix.of(SoundId.BROWN to 0.7f, SoundId.FAN to 0.4f)),
        Scene("summer_night", "Summer night", Mix.of(SoundId.CRICKETS to 0.6f, SoundId.STREAM to 0.35f)),
        Scene("long_haul", "Long haul", Mix.of(SoundId.AIRPLANE to 0.75f, SoundId.PINK to 0.25f)),
    )

    fun byId(id: String): Scene? = all.firstOrNull { it.id == id }

    /** The scene whose mix equals [mix] (layer order-insensitive, gains exact), if any. */
    fun matching(mix: Mix): Scene? = all.firstOrNull { it.mix.layers.toSet() == mix.layers.toSet() }
}
