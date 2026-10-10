package com.hisbaan.orbit.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Keeps background voices (a TV, people across the room) from reaching the recognizer, by
 * level: the user speaks close to the mic, so their voice arrives well above anything farther
 * away. Audio more than [marginDb] below the user's level, past a short hangover, is replaced
 * with silence; the recognizer then hears the user stop, ends the utterance, and never
 * transcribes the TV after (or instead of) the query.
 *
 * The user's level is learned from what they say: the loud end of the last [WINDOW_CHUNKS] of
 * sound above the noise floor, so a cough or a knock doesn't count. It only goes up, and one
 * gate lasts a whole turn, so a follow-up listen isn't answered by whoever is talking across the
 * room. Until it has heard enough of the user it lets everything through.
 *
 * [admit] is called from the capture thread, the rest from the turn; all are synchronized.
 */
class VoiceGate(private val marginDb: Float = DEFAULT_MARGIN_DB) {
    /** The last [SMOOTH_CHUNKS] chunks' power, for a level that doesn't jump on a single click. */
    private val recent = FloatArray(SMOOTH_CHUNKS)
    private var recentCount = 0
    private var recentNext = 0

    /** Levels of the last [WINDOW_CHUNKS] chunks that stood out from the floor. */
    private val active = FloatArray(WINDOW_CHUNKS)
    private var activeCount = 0
    private var activeNext = 0

    private var floorDb = Float.NaN
    private var voiceDb = Float.NEGATIVE_INFINITY
    private var hold = 0

    // This listen, for the log.
    private var samples = 0L
    private var heldBack = 0L
    private var spanStart = -1L
    private val spans = mutableListOf<LongRange>()

    /** Starts a listen: the voice level and floor carry over; the last listen's audio doesn't. */
    @Synchronized
    fun startListen() {
        recentCount = 0
        recentNext = 0
        samples = 0
        heldBack = 0
        spanStart = -1
        spans.clear()
        hold = 0
    }

    /** Whether a chunk of [count] samples at [rmsDbfs] should be heard; if not, feed silence instead. */
    @Synchronized
    fun admit(count: Int, rmsDbfs: Float): Boolean {
        recent[recentNext] = 10f.pow(rmsDbfs / 10)
        recentNext = (recentNext + 1) % SMOOTH_CHUNKS
        recentCount = minOf(recentCount + 1, SMOOTH_CHUNKS)
        val level = 10 * log10(max(recent.take(recentCount).average().toFloat(), 1e-10f))

        floorDb = if (floorDb.isNaN() || level < floorDb) level else floorDb + FLOOR_RISE_DB
        if (level > floorDb + ACTIVE_DB && level > MIN_VOICE_DBFS) {
            active[activeNext] = level
            activeNext = (activeNext + 1) % WINDOW_CHUNKS
            activeCount = minOf(activeCount + 1, WINDOW_CHUNKS)
            if (activeCount == WINDOW_CHUNKS) voiceDb = max(voiceDb, active.sorted()[(WINDOW_CHUNKS - 1) * 4 / 5])
        }

        val loud = level >= voiceDb - marginDb
        if (loud) hold = HANGOVER_CHUNKS
        val pass = loud || hold > 0
        if (!loud && hold > 0) hold--

        if (!pass) {
            heldBack += count
            if (spanStart < 0) spanStart = samples
        } else if (spanStart >= 0) {
            spans += spanStart until samples
            spanStart = -1
        }
        samples += count
        return pass
    }

    /** What this listen held back, for the turn log. */
    @Synchronized
    fun summary(): String {
        val floor = if (floorDb.isNaN()) "?" else "%.0f".format(floorDb)
        if (voiceDb == Float.NEGATIVE_INFINITY) return "Voice gate: hadn't heard enough of the voice to gate (floor $floor dBFS)"
        val all = spans + listOfNotNull(if (spanStart >= 0) spanStart until samples else null)
        val shown = all.filter { it.last - it.first >= MicCapture.SAMPLE_RATE / 5 }
        return "Voice gate: voice %.0f dBFS, floor %s, passing above %.0f; held back %d%%".format(
            voiceDb, floor, voiceDb - marginDb, if (samples > 0) heldBack * 100 / samples else 0,
        ) + if (shown.isEmpty()) "" else shown.take(MAX_SPANS_LOGGED).joinToString(prefix = " (", postfix = if (shown.size > MAX_SPANS_LOGGED) ", …)" else ")") {
            "%.1f-%.1fs".format(it.first / MicCapture.SAMPLE_RATE.toFloat(), (it.last + 1) / MicCapture.SAMPLE_RATE.toFloat())
        }
    }

    companion object {
        /** How far below the user's voice audio is taken to be someone else. */
        const val DEFAULT_MARGIN_DB = 15f
        /** 100 ms of 20 ms chunks. */
        private const val SMOOTH_CHUNKS = 5
        /** 500 ms of sound above the floor makes a voice level. */
        private const val WINDOW_CHUNKS = 25
        /** Keep passing 300 ms after the voice drops: soft word endings, gaps between words. */
        private const val HANGOVER_CHUNKS = 15
        /** How far above the floor a chunk counts as sound rather than room noise. */
        private const val ACTIVE_DB = 10f
        private const val MIN_VOICE_DBFS = -60f
        /** The floor follows quiet down at once and creeps back up at 1 dB/s. */
        private const val FLOOR_RISE_DB = 0.02f
        private const val MAX_SPANS_LOGGED = 6
    }
}
