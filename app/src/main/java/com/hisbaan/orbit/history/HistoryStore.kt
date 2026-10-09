package com.hisbaan.orbit.history

import com.hisbaan.orbit.diagnostics.EventLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects the conversation keys: anyone can wrap one (the assistant records on a locked
 * phone), only the user can unwrap them, by authenticating. [KeystoreHistoryKeys] on the phone.
 */
interface HistoryKeys {
    /** Whether the phone has a screen lock. Without one there's nothing to tie the keys to, so nothing is kept. */
    val deviceSecure: Boolean

    /** False if there are no keys, or the screen lock changed since they were made: what they wrapped is lost. */
    fun valid(): Boolean

    /** Wraps a new conversation's key, making the keys first if there are none. */
    fun wrap(key: ByteArray): ByteArray

    /**
     * Throws [HistoryLockedException] if the user hasn't authenticated just now, and
     * [HistoryKeysLostException] if the keys are gone or invalidated.
     */
    fun unwrap(wrapped: ByteArray): ByteArray

    fun delete()
}

/** Reading the history needs the user to authenticate first (or again: the window is short). */
class HistoryLockedException(cause: Throwable? = null) : Exception("Authenticate to read the history", cause)

/** The keys were invalidated (the screen lock was removed) or deleted: the history can't be read and has been cleared. */
class HistoryKeysLostException(cause: Throwable? = null) : Exception("The history's keys are gone", cause)

/**
 * The conversation history: one file per conversation in [dir], every turn in it encrypted
 * under the conversation's own AES-GCM key, which is stored wrapped by [keys]. Writing needs
 * no authentication; reading back needs the user's PIN or biometric. Kept [RETENTION_MS] and at
 * most [MAX_CONVERSATIONS]; nothing is kept on a phone without a screen lock.
 *
 * File: `ORBH`, version, wrapped key (length-prefixed), then one length-prefixed record per
 * turn: IV + AES-GCM ciphertext of the turn's JSON, authenticated with the file id and the
 * record's index so records can't be swapped between or within files. A record cut short (the
 * app died mid-write) ends the file.
 *
 * Calls block on disk and the Keystore: run them off the main thread.
 */
class HistoryStore(
    private val dir: File,
    private val keys: HistoryKeys,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The conversation turns are being added to. Its key stays in memory meanwhile, like the conversation itself. */
    private class Open(val id: String, val file: File, val key: SecretKeySpec, var records: Int, var lastEndedAt: Long)

    private var open: Open? = null
    private val random = SecureRandom()

    /** What [load] found. [incomplete]: the authentication ran out before every conversation was read. */
    data class Loaded(val conversations: List<StoredConversation>, val incomplete: Boolean)

    /**
     * Adds [turn] to the open conversation, or starts a new one if [startsConversation] (the
     * model started afresh), there is none, or the turn never reached the model and the last
     * one was long enough ago that the model would have forgotten it.
     */
    @Synchronized
    fun append(turn: TurnRecord, startsConversation: Boolean) {
        if (!keys.deviceSecure) {
            // No screen lock: nothing to protect the history with. What's kept can't be read anymore either.
            if (open != null || conversationFiles().isNotEmpty()) wipe("no screen lock")
            return
        }
        val current = open?.takeIf {
            !startsConversation && it.file.exists() && (turn.reachedModel || turn.startedAt - it.lastEndedAt <= JOIN_MS)
        } ?: startConversation(turn.startedAt)
        val plain = historyJson.encodeToString(TurnRecord.serializer(), turn).toByteArray()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES).apply {
            init(Cipher.ENCRYPT_MODE, current.key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(current.id, current.records))
        }
        val sealed = iv + cipher.doFinal(plain)
        // One write, so a crash leaves at most a cut-short record at the end.
        FileOutputStream(current.file, true).use { it.write(bytes { writeInt(sealed.size); write(sealed) }) }
        current.records++
        current.lastEndedAt = turn.endedAt
    }

    private fun startConversation(startedAt: Long): Open {
        // Keys invalidated by a screen lock change: what they wrapped is unreadable; start over.
        if (!keys.valid()) wipe("keys invalid")
        prune()
        val id = "$startedAt-${ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }}"
        val raw = ByteArray(32).also(random::nextBytes)
        val key = SecretKeySpec(raw, "AES") // copies it
        val wrapped = keys.wrap(raw)
        raw.fill(0)
        dir.mkdirs()
        val file = File(dir, "$id$EXTENSION")
        file.writeBytes(bytes { write(MAGIC); writeByte(VERSION.toInt()); writeInt(wrapped.size); write(wrapped) })
        return Open(id, file, key, 0, startedAt).also { open = it }
    }

    /**
     * Every conversation, newest first, decrypted. Needs the user to have just authenticated:
     * throws [HistoryLockedException] if they haven't, and [HistoryKeysLostException] (after
     * clearing the history) if the keys are gone.
     */
    @Synchronized
    fun load(): Loaded {
        prune()
        val conversations = mutableListOf<StoredConversation>()
        for (file in conversationFiles().sortedByDescending { startOf(it) }) {
            val conversation = try {
                read(file)
            } catch (e: HistoryLockedException) {
                // The first needs authentication; later ones ran out of time.
                if (conversations.isEmpty()) throw e
                return Loaded(conversations, incomplete = true)
            } catch (e: HistoryKeysLostException) {
                wipe("keys lost")
                throw e
            } catch (e: Exception) {
                damaged(file, e::class.simpleName ?: "unreadable")
            }
            conversation?.let(conversations::add)
        }
        return Loaded(conversations, incomplete = false)
    }

    @Synchronized
    fun delete(id: String) {
        if (open?.id == id) open = null
        File(dir, "$id$EXTENSION").delete()
    }

    /** Deletes every conversation. The keys stay: a new conversation can use them without asking. */
    @Synchronized
    fun clear() {
        open = null
        conversationFiles().forEach { it.delete() }
    }

    /** Drops conversations older than [RETENTION_MS] and beyond the newest [MAX_CONVERSATIONS]. */
    @Synchronized
    fun prune() {
        val files = conversationFiles().sortedByDescending { startOf(it) }
        val cutoff = clock() - RETENTION_MS
        files.filterIndexed { i, file -> i >= MAX_CONVERSATIONS || startOf(file) < cutoff }.forEach { file ->
            if (open?.file == file) open = null
            file.delete()
        }
    }

    /** Null if the file is damaged beyond its first record. */
    private fun read(file: File): StoredConversation? {
        val id = file.name.removeSuffix(EXTENSION)
        val input = DataInputStream(file.readBytes().inputStream())
        val wrapped = try {
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC) || input.readByte() != VERSION) return damaged(file, "unknown format")
            ByteArray(input.readInt()).also(input::readFully)
        } catch (e: EOFException) {
            return damaged(file, "cut short")
        }
        val key = SecretKeySpec(keys.unwrap(wrapped), "AES")
        val turns = mutableListOf<TurnRecord>()
        while (true) {
            val sealed = try {
                ByteArray(input.readInt()).also(input::readFully)
            } catch (e: EOFException) {
                break // the end, or a record cut short by a crash
            }
            val turn = try {
                val cipher = Cipher.getInstance(AES).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                    updateAAD(aad(id, turns.size))
                }
                historyJson.decodeFromString(TurnRecord.serializer(), String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)))
            } catch (e: Exception) {
                EventLog.log("history", "Stopped reading a conversation at turn ${turns.size}: ${e::class.simpleName}")
                break
            }
            turns += turn
        }
        if (turns.isEmpty()) return null
        return StoredConversation(id, startOf(file), turns)
    }

    private fun damaged(file: File, why: String): StoredConversation? {
        EventLog.log("history", "Skipped a damaged conversation file ($why)")
        return null
    }

    /** Deletes every conversation and the keys, which make new ones next time. */
    private fun wipe(why: String) {
        val files = conversationFiles()
        if (files.isNotEmpty()) EventLog.log("history", "Clearing the history: $why")
        open = null
        files.forEach { it.delete() }
        keys.delete()
    }

    private fun conversationFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(EXTENSION) }?.toList().orEmpty()

    private fun startOf(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: 0

    private fun aad(id: String, index: Int) = "$id:$index".toByteArray()

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_CONVERSATIONS = 200

        /** A turn that never reached the model joins the open conversation if it's this recent (the model's own memory). */
        const val JOIN_MS = 2 * 60_000L

        private const val EXTENSION = ".orbh"
        private val MAGIC = "ORBH".toByteArray()
        private const val VERSION: Byte = 1
        private const val AES = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
