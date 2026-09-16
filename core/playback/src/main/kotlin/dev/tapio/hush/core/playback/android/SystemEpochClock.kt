package dev.tapio.hush.core.playback.android

import dev.tapio.hush.core.playback.Clock

/**
 * Real wall clock. Wall time (not elapsed-realtime) is deliberate: the sleep
 * timer's end instant is persisted and has to survive a process death.
 */
class SystemEpochClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
