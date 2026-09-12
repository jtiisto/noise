@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.jtiisto.noise.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A controller wired to fakes and to the test scheduler's virtual time.
 *
 * The controller's `mainDispatcher` is an [UnconfinedTestDispatcher]: that is
 * the test-time equivalent of the production `Dispatchers.Main.immediate`,
 * where a command issued from the main thread runs inline. `delay` still uses
 * virtual time, so the sleep timer and the persistence debounce stay
 * deterministic.
 */
class PlaybackFixture(
    scheduler: TestCoroutineScheduler,
    persisted: PersistedState = PersistedState(),
) {
    val engine = FakeAudioEngine()
    val store = FakeStateStore(persisted)
    val focus = FakeAudioFocusGate()
    val launcher = FakeServiceLauncher()
    val clock = VirtualClock(scheduler)

    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    val controller: PlaybackController = DefaultPlaybackController(
        engine = engine,
        store = store,
        focus = focus,
        serviceLauncher = launcher,
        clock = clock,
        scope = scope,
        mainDispatcher = UnconfinedTestDispatcher(scheduler),
    )

    val state: PlaybackState get() = controller.state.value

    fun close() = scope.cancel()
}

/**
 * Runs [body] against a fresh fixture and always tears the controller's scope
 * down: a running sleep timer schedules a tick forever, which would keep the
 * scheduler from ever going idle.
 */
fun playbackTest(
    persisted: PersistedState = PersistedState(),
    body: suspend TestScope.(PlaybackFixture) -> Unit,
): TestResult = runTest(StandardTestDispatcher()) {
    val fixture = PlaybackFixture(testScheduler, persisted)
    try {
        body(fixture)
    } finally {
        fixture.close()
    }
}

/**
 * Advances virtual time and also runs whatever is scheduled exactly at the new
 * instant — `advanceTimeBy` alone stops just short of it, which would leave the
 * one-second tick at that boundary unrun.
 */
fun TestScope.advanceThrough(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}
