package com.abhinavxt.newsforge.listen

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.abhinavxt.newsforge.core.listen.Narration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * One thing to read aloud.
 *
 * @param clusterId the story it came from, when it is one — what lets catch-up follow
 *   along, turning to the card being read.
 */
data class ListenItem(
    val title: String,
    val text: String,
    val clusterId: String? = null,
)

data class NarratorState(
    val items: List<ListenItem> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    /** False once the device turns out to have no usable speech engine. */
    val available: Boolean = true,
) {
    val current: ListenItem? get() = items.getOrNull(index)
    val active: Boolean get() = items.isNotEmpty()
}

/**
 * Listen mode: reads stories and articles aloud with the device's speech engine.
 *
 * App-scoped, so turning the phone or changing tab does not cut a story off mid-sentence.
 * The engine starts on first use rather than with the app — binding it costs a service
 * connection and a few hundred milliseconds that nobody who never presses Listen should
 * pay.
 *
 * The engine has no pause, only stop. Pausing therefore stops and remembers which chunk
 * was being spoken, and resuming starts that chunk again: a few seconds repeated, which is
 * how every podcast app's "back 5 seconds" feels anyway, and better than restarting a long
 * article from its headline.
 */
class Narrator(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var engineReady = false

    /** Chunks of the current item, and the one last started. Main thread only. */
    private var chunks: List<String> = emptyList()
    private var chunk = 0

    /**
     * Bumped on every new speak. Callbacks carry the generation they were queued under,
     * so the tail of a story that was skipped cannot arrive late and skip the next one.
     */
    private var generation = 0

    private val _state = MutableStateFlow(NarratorState())
    val state: StateFlow<NarratorState> = _state.asStateFlow()

    fun play(items: List<ListenItem>, start: Int = 0) {
        if (items.isEmpty()) return
        _state.value = NarratorState(items = items, index = start.coerceIn(0, items.lastIndex), playing = true)
        startItem()
    }

    fun toggle() {
        val current = _state.value
        if (!current.active) return
        if (current.playing) pause() else resume()
    }

    fun pause() {
        generation++
        engine?.stop()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        if (!_state.value.active) return
        _state.update { it.copy(playing = true) }
        speakFrom(chunk)
    }

    fun next() {
        val current = _state.value
        if (current.index >= current.items.lastIndex) {
            stop()
            return
        }
        _state.update { it.copy(index = it.index + 1) }
        startItem()
    }

    fun previous() {
        val current = _state.value
        // Like a music player: back restarts the story unless it has only just begun.
        if (chunk == 0 && current.index > 0) {
            _state.update { it.copy(index = it.index - 1) }
        }
        startItem()
    }

    fun stop() {
        generation++
        engine?.stop()
        chunks = emptyList()
        chunk = 0
        // Clears "unavailable" too: dismissing that message is the reader acknowledging
        // it, and the next Listen tries a fresh engine in case one has been installed.
        _state.value = NarratorState()
    }

    /** Releases the engine; the next [play] binds a new one. */
    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        engineReady = false
    }

    private fun startItem() {
        val item = _state.value.current ?: return
        chunks = Narration.chunks(item.text)
        chunk = 0
        if (_state.value.playing) speakFrom(0)
    }

    private fun speakFrom(from: Int) {
        val tts = engine ?: createEngine()
        if (!engineReady) return // onInit picks up from here
        generation++
        val tag = generation
        tts.stop()
        if (chunks.isEmpty()) {
            next()
            return
        }
        for (i in from until chunks.size) {
            val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunks[i], mode, null, "$tag#$i")
        }
    }

    private fun createEngine(): TextToSpeech {
        val tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post { if (isCurrent(utteranceId)) chunk = chunkOf(utteranceId) }
            }

            override fun onDone(utteranceId: String) {
                main.post {
                    if (isCurrent(utteranceId) && chunkOf(utteranceId) == chunks.lastIndex &&
                        _state.value.playing
                    ) {
                        next()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                // One bad chunk should cost one story, not the whole session.
                main.post { if (isCurrent(utteranceId) && _state.value.playing) next() }
            }
        })
        engine = tts
        return tts
    }

    private fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "No speech engine: $status")
            engineReady = false
            // Released, so the next play binds afresh rather than waiting on an engine
            // that has already said no.
            tts.shutdown()
            engine = null
            _state.value = NarratorState(available = false)
            return
        }
        // Indian English first, because the feeds are mostly Indian outlets and the names
        // in them are pronounced better; the device default otherwise.
        val india = tts.setLanguage(Locale("en", "IN"))
        if (india == TextToSpeech.LANG_MISSING_DATA || india == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        engineReady = true
        if (_state.value.playing) speakFrom(chunk)
    }

    private fun isCurrent(utteranceId: String): Boolean =
        utteranceId.substringBefore('#').toIntOrNull() == generation

    private fun chunkOf(utteranceId: String): Int =
        utteranceId.substringAfter('#').toIntOrNull() ?: 0

    private companion object {
        const val TAG = "Narrator"
    }
}
