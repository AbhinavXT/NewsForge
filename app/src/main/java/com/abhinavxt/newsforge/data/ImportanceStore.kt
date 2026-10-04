package com.abhinavxt.newsforge.data

import android.content.Context
import com.abhinavxt.newsforge.core.learn.ImportanceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the learned weights live, and the one flow everything reads them from.
 *
 * Its own class rather than part of [ImportanceRepository] because two things need it
 * pointing in opposite directions: the feed ranks with the model, and training reads the
 * feed to produce the model. Held in either one, that is a construction cycle; held here,
 * both depend on this and neither on the other.
 */
class ImportanceStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("importance", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(ImportanceModel.decode(prefs.getString(KEY_MODEL, null)))

    fun model(): StateFlow<ImportanceModel> = state.asStateFlow()

    val current: ImportanceModel get() = state.value

    fun save(model: ImportanceModel) {
        state.value = model
        prefs.edit().putString(KEY_MODEL, ImportanceModel.encode(model)).apply()
    }

    fun reset() {
        state.value = ImportanceModel()
        prefs.edit().remove(KEY_MODEL).apply()
    }

    private companion object {
        /**
         * Versioned, though [ImportanceModel.decode] already discards a vector of the
         * wrong length. A change that keeps the length and alters what a slot means —
         * reordering two features — would pass that check and be silently wrong, and
         * bumping this key is how such a change says so.
         */
        const val KEY_MODEL = "model_v1"
    }
}
