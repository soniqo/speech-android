package audio.soniqo.speech.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Push-to-talk visual state. */
enum class MicState { OFFLINE, IDLE, LISTENING, THINKING, SPEAKING }

sealed interface FeedItem { val id: Long }

/** Instrumentation note (bench lines, hints, errors). */
data class SystemNote(override val id: Long, val text: String) : FeedItem

/** One voice/typed command, filled in as the turn progresses. */
data class Turn(
    override val id: Long,
    val utterance: String,
    val toolLabel: String? = null,
    val spoken: String? = null,
    val metrics: TurnMetrics? = null,
    val failed: Boolean = false,
) : FeedItem

data class ControlUiState(
    val status: String = "initializing",
    val micState: MicState = MicState.OFFLINE,
    val micLevel: Float = 0f,
    /** null = no download in flight. */
    val downloadPercent: Int? = null,
    /** Friendly label of the model currently downloading (for the setup panel). */
    val downloadStage: String? = null,
    /** `412 / 580 MB · 3.6 MB/s · 2 min left`, or null before the rate settles. */
    val downloadDetail: String? = null,
    val memNowMb: Int = 0,
    val memPeakMb: Int = 0,
    /** Latencies of the most recent completed turn, for the status line. */
    val lastMetrics: TurnMetrics? = null,
    val feed: List<FeedItem> = emptyList(),
    val showTypeDialog: Boolean = false,
    val showInfoDialog: Boolean = false,
)

/**
 * Single source of truth for the screen — the zustand analog: one state
 * flow, small named mutations, UI subscribes and recomposes. All methods are
 * safe to call from any thread.
 */
class ControlStore {

    private val _state = MutableStateFlow(ControlUiState())
    val state: StateFlow<ControlUiState> = _state

    private var nextId = 1L
    private fun id(): Long = synchronized(this) { nextId++ }

    fun setStatus(text: String) = _state.update { it.copy(status = text) }

    fun setMic(mic: MicState) = _state.update { it.copy(micState = mic) }

    fun setMicLevel(level: Float) = _state.update { it.copy(micLevel = level) }

    fun setDownload(percent: Int?) = _state.update { it.copy(downloadPercent = percent) }

    fun setDownloadStage(stage: String?) = _state.update { it.copy(downloadStage = stage) }

    fun setDownloadDetail(detail: String?) = _state.update { it.copy(downloadDetail = detail) }

    fun setMemory(nowMb: Int, peakMb: Int) =
        _state.update { it.copy(memNowMb = nowMb, memPeakMb = peakMb) }

    fun setLastMetrics(metrics: TurnMetrics) =
        _state.update { it.copy(lastMetrics = metrics) }

    fun setTypeDialog(visible: Boolean) = _state.update { it.copy(showTypeDialog = visible) }

    fun setInfoDialog(visible: Boolean) = _state.update { it.copy(showInfoDialog = visible) }

    fun addNote(text: String) = _state.update {
        it.copy(feed = it.feed + SystemNote(id(), text))
    }

    /** Start a turn card; returns its id for the follow-up updates. */
    fun beginTurn(utterance: String): Long {
        val turnId = id()
        _state.update { it.copy(feed = it.feed + Turn(turnId, utterance)) }
        return turnId
    }

    fun updateTurn(turnId: Long, transform: (Turn) -> Turn) = _state.update { s ->
        s.copy(feed = s.feed.map { if (it is Turn && it.id == turnId) transform(it) else it })
    }
}
