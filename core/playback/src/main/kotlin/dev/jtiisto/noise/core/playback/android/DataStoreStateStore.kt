package dev.jtiisto.noise.core.playback.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jtiisto.noise.core.playback.PersistedState
import dev.jtiisto.noise.core.playback.PersistedStateCodec
import dev.jtiisto.noise.core.playback.StateStore
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

    override suspend fun load(): PersistedState = PersistedStateCodec.decode(dataStore.data.first())

    override suspend fun save(state: PersistedState) {
        dataStore.edit { PersistedStateCodec.encode(state, it) }
    }

    private companion object {
        /** DataStore appends ".preferences_pb" — see specs/persistence.md. */
        const val FILE_NAME = "hush"
    }
}
