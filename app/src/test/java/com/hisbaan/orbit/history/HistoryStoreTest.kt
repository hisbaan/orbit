package com.hisbaan.orbit.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class HistoryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The Keystore's part in software: a key pair, a screen lock, and whether the user just authenticated. */
    private class FakeKeys : HistoryKeys {
        var pair: KeyPair? = null
        override var deviceSecure = true
        var authenticated = true
        var invalidated = false

        /** Unwraps allowed before the authentication window runs out; null for no limit. */
        var unwrapsLeft: Int? = null

        override fun valid() = pair != null && !invalidated

        override fun wrap(key: ByteArray): ByteArray {
            val public = (pair ?: KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also { pair = it }).public
            return Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, public, OAEP) }.doFinal(key)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            val private = pair?.private?.takeIf { !invalidated } ?: throw HistoryKeysLostException()
            if (!authenticated || unwrapsLeft == 0) throw HistoryLockedException()
            unwrapsLeft = unwrapsLeft?.minus(1)
            return Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, private, OAEP) }.doFinal(wrapped)
        }

        override fun delete() {
            pair = null
            invalidated = false
        }

        companion object {
            const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
            val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        }
    }

    private val keys = FakeKeys()
    private var now = DAY * 100
    private val dir by lazy { File(folder.root, "history") }
    private val store by lazy { HistoryStore(dir, keys, clock = { now }) }

    private fun turn(said: String, at: Long = now, model: Boolean = true) = TurnRecord(
        startedAt = at,
        endedAt = at + 1_000,
        source = "test",
        entries = listOfNotNull(
            Entry(at, HistoryEvent.Heard(said)),
            if (model) Entry(at + 10, HistoryEvent.ModelStep(step = 1, model = "m", systemPrompt = "sys", text = "ok")) else null,
            Entry(at + 20, HistoryEvent.Reply("ok")),
        ),
    )

    private fun files() = dir.listFiles().orEmpty().toList()

    @Test
    fun `turns read back in their conversations, newest conversation first`() {
        store.append(turn("first"), startsConversation = true)
        now += 30_000
        store.append(turn("follow-up"), startsConversation = false)
        now += 10 * 60_000
        store.append(turn("later"), startsConversation = true)

        val loaded = store.load()
        assertFalse(loaded.incomplete)
        assertEquals(listOf(listOf("later"), listOf("first", "follow-up")), loaded.conversations.map { c -> c.turns.map { it.heard() } })
        assertEquals("sys", (loaded.conversations[1].turns[0].entries[1].event as HistoryEvent.ModelStep).systemPrompt)
    }

    @Test
    fun `nothing in the files is readable without the key`() {
        store.append(turn("call my sister"), startsConversation = true)
        val bytes = files().single().readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sister"))
    }

    @Test
    fun `a turn that never reached the model joins only a recent conversation`() {
        store.append(turn("timer"), startsConversation = true)
        now += 60_000
        store.append(turn("(silence)", model = false), startsConversation = false)
        now += 10 * 60_000
        store.append(turn("(silence again)", model = false), startsConversation = false)

        assertEquals(listOf(listOf("(silence again)"), listOf("timer", "(silence)")), store.load().conversations.map { c -> c.turns.map { it.heard() } })
    }

    @Test
    fun `reading needs the user to have authenticated`() {
        store.append(turn("hi"), startsConversation = true)
        keys.authenticated = false
        assertThrows<HistoryLockedException> { store.load() }
        keys.authenticated = true
        assertEquals(1, store.load().conversations.size)
    }

    @Test
    fun `what loads before the authentication runs out is kept`() {
        repeat(3) {
            store.append(turn("turn $it"), startsConversation = true)
            now += 60_000
        }
        keys.unwrapsLeft = 2
        val loaded = store.load()
        assertTrue(loaded.incomplete)
        assertEquals(listOf("turn 2", "turn 1"), loaded.conversations.map { it.turns.single().heard() })
    }

    @Test
    fun `a record cut short by a crash ends the conversation there`() {
        store.append(turn("one"), startsConversation = true)
        store.append(turn("two"), startsConversation = false)
        val file = files().single()
        file.writeBytes(file.readBytes().dropLast(5).toByteArray())
        assertEquals(listOf("one"), store.load().conversations.single().turns.map { it.heard() })
    }

    @Test
    fun `a record moved within a file doesn't decrypt`() {
        store.append(turn("one"), startsConversation = true)
        val file = files().single()
        val one = file.readBytes()
        store.append(turn("two"), startsConversation = false)
        val both = file.readBytes()
        // Header, then record two where record one was: its index no longer matches.
        val header = one.copyOfRange(0, headerSize(one))
        val first = one.copyOfRange(header.size, one.size)
        val second = both.copyOfRange(one.size, both.size)
        file.writeBytes(header + second + first)
        assertTrue(store.load().conversations.isEmpty())
    }

    @Test
    fun `without a screen lock nothing is kept, and what was kept is cleared`() {
        store.append(turn("hi"), startsConversation = true)
        keys.deviceSecure = false
        store.append(turn("again"), startsConversation = false)
        assertTrue(files().isEmpty())
        assertEquals(null, keys.pair)

        keys.deviceSecure = true
        store.append(turn("with a lock"), startsConversation = false)
        assertEquals(listOf("with a lock"), store.load().conversations.map { it.turns.single().heard() })
    }

    @Test
    fun `keys invalidated by a screen lock change clear the history, which starts again`() {
        store.append(turn("old"), startsConversation = true)
        keys.invalidated = true
        assertThrows<HistoryKeysLostException> { store.load() }
        assertTrue(files().isEmpty())

        store.append(turn("old again"), startsConversation = true)
        keys.invalidated = true
        // Starting a conversation notices before wrapping with the dead key.
        store.append(turn("new"), startsConversation = true)
        assertEquals(listOf("new"), store.load().conversations.map { it.turns.single().heard() })
    }

    @Test
    fun `keeps a week and at most the newest conversations`() {
        store.append(turn("old"), startsConversation = true)
        now += 8 * DAY
        repeat(HistoryStore.MAX_CONVERSATIONS + 1) {
            store.append(turn("new $it"), startsConversation = true)
            now += 1_000
        }
        store.prune()
        val left = files().size
        assertEquals(HistoryStore.MAX_CONVERSATIONS, left)
        assertFalse(store.load().conversations.any { c -> c.turns.single().heard() in setOf("old", "new 0") })
    }

    @Test
    fun `delete and clear remove conversations, and recording carries on`() {
        store.append(turn("a"), startsConversation = true)
        now += 10 * 60_000
        store.append(turn("b"), startsConversation = true)
        val (b, _) = store.load().conversations
        store.delete(b.id)
        assertEquals(listOf("a"), store.load().conversations.map { it.turns.single().heard() })

        store.clear()
        assertTrue(store.load().conversations.isEmpty())
        store.append(turn("c"), startsConversation = false)
        assertEquals(listOf("c"), store.load().conversations.map { it.turns.single().heard() })
    }

    private fun TurnRecord.heard() = entries.firstNotNullOf { (it.event as? HistoryEvent.Heard)?.text }

    /** Magic, version, then the length-prefixed wrapped key. */
    private fun headerSize(file: ByteArray): Int {
        val length = java.nio.ByteBuffer.wrap(file, 5, 4).int
        return 5 + 4 + length
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
