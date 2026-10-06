package com.hisbaan.orbit.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** How Orbit marks the start and end of listening. */
enum class EarconStyle(val label: String) {
    /** No sound: the overlay and the follow-up behaviour are the only cues. */
    SILENT("Silent"),

    /** Soft, low chimes that decay quickly: enough when the headset is quiet (earbuds, phone). */
    GENTLE("Gentle"),

    /** Louder, brighter and higher, to cut through wind and engine noise in a helmet. */
    ALERTING("Alerting"),
}

/**
 * The listening sounds, synthesized: two notes rising when Orbit starts listening, falling
 * when it stops. Every note has a short attack and a smooth release, so nothing clicks.
 */
object Earcons {
    fun listening(style: EarconStyle, sampleRate: Int): ShortArray = when (style) {
        EarconStyle.SILENT -> ShortArray(0)
        EarconStyle.GENTLE -> chime(sampleRate, listOf(E5, A5))
        EarconStyle.ALERTING -> alert(sampleRate, listOf(A5, D6))
    }

    fun done(style: EarconStyle, sampleRate: Int): ShortArray = when (style) {
        EarconStyle.SILENT -> ShortArray(0)
        EarconStyle.GENTLE -> chime(sampleRate, listOf(A5, E5))
        EarconStyle.ALERTING -> alert(sampleRate, listOf(D6, A5))
    }

    /** Bell-like notes: a sine with a touch of octave, fading out on their own. */
    private fun chime(sampleRate: Int, notes: List<Double>) = mix(
        sampleRate,
        notes.mapIndexed { i, hz -> Note(hz, startMs = i * 85, lengthMs = 260, amplitude = 0.2, decayMs = 70.0, harmonics = listOf(1.0 to 1.0, 2.0 to 0.12)) },
    )

    /**
     * Held notes, louder and a little brighter than the chimes, to carry over noise. Kept
     * around 1 kHz with only light harmonics: higher and buzzier (D6→G6, a strong third
     * harmonic) was too harsh in use.
     */
    private fun alert(sampleRate: Int, notes: List<Double>) = mix(
        sampleRate,
        notes.mapIndexed { i, hz ->
            Note(hz, startMs = i * 145, lengthMs = 130, amplitude = 0.45, decayMs = 250.0, harmonics = listOf(1.0 to 1.0, 2.0 to 0.2, 3.0 to 0.08))
        },
    )

    private class Note(
        val hz: Double,
        val startMs: Int,
        val lengthMs: Int,
        val amplitude: Double,
        /** Time constant of the exponential fade after the attack. */
        val decayMs: Double,
        /** (multiple of [hz], relative level) pairs. */
        val harmonics: List<Pair<Double, Double>>,
    )

    private fun mix(sampleRate: Int, notes: List<Note>): ShortArray {
        val total = notes.maxOf { it.startMs + it.lengthMs } * sampleRate / 1000
        val out = DoubleArray(total)
        val attack = sampleRate * ATTACK_MS / 1000
        val release = sampleRate * RELEASE_MS / 1000
        for (note in notes) {
            val start = note.startMs * sampleRate / 1000
            val length = note.lengthMs * sampleRate / 1000
            val level = note.amplitude / note.harmonics.sumOf { it.second }
            for (i in 0 until length) {
                val t = i.toDouble() / sampleRate
                val envelope = min(1.0, i.toDouble() / attack) * min(1.0, (length - i).toDouble() / release) *
                    exp(-t * 1000 / note.decayMs)
                val wave = note.harmonics.sumOf { (multiple, weight) -> weight * sin(2 * PI * note.hz * multiple * t) }
                out[start + i] += wave * envelope * level
            }
        }
        return ShortArray(total) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    private const val ATTACK_MS = 8
    private const val RELEASE_MS = 25

    private const val E5 = 659.26
    private const val A5 = 880.0
    private const val D6 = 1174.66
}
