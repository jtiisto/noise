package dev.tapio.hush.core.playback.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import android.util.Log
import java.io.IOException
import dev.tapio.hush.core.playback.PersistedState
import dev.tapio.hush.core.playback.PersistedStateCodec
import dev.tapio.hush.core.playback.StateStore
import kotlinx.coroutines.flow.first

/**
 * Preferences DataStore backing for [StateStore]. Device glue only — every
 * decision lives in [PersistedStateCodec], which is unit tested.
 *
 * A corrupt file is replaced with an empty one rather than crashing the app on
 * launch; losing the last mix beats not starting.
 */
class DataStoreStateStore(context: Context) : StateStore {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
    )

    // Corruption is replaced above; a plain read/write failure (full disk,
    // storage unmounted) is logged and swallowed. The application scope has
    // no exception handler, so letting either escape would crash the app,
    // and losing one save or coming up with defaults is the lesser evil.
    override suspend fun load(): PersistedState = try {
        PersistedStateCodec.decode(dataStore.data.first())
    } catch (e: IOException) {
        Log.w(TAG, "could not read persisted state; using defaults", e)
        PersistedState()
    }

    override suspend fun save(state: PersistedState) {
        try {
            dataStore.edit { PersistedStateCodec.encode(state, it) }
        } catch (e: IOException) {
            Log.w(TAG, "could not persist state", e)
        }
    }

    private companion object {
        const val TAG = "HushStateStore"

        /** DataStore appends ".preferences_pb" — see specs/persistence.md. */
        const val FILE_NAME = "hush"
    }
}
