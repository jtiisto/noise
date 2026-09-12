plugins {
    id("noise.android.library")
}

// Pure data: the sound catalog, mixes and scenes. No Android or Compose
// dependencies so every consumer (engine, service, UI, tests) can share it.
