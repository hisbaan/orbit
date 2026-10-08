package com.hisbaan.orbit.tools

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.util.Base64
import android.view.View
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.agent.booleanProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.diagnostics.EventLog
import com.hisbaan.orbit.providers.ChatImage
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What was on screen when Orbit's overlay opened, from the system's assist data: the app's
 * view text and a screenshot. Requires the user's "Use screen and app data" assistant setting
 * and, from Android 17, the usesAssist* declarations in voice_interaction_service.xml;
 * otherwise the system delivers nulls. Kept in memory only while the overlay is up, and only
 * sent to the model when it calls [ReadScreenTool].
 */
object ScreenContext {
    data class State(
        val expectingText: Boolean = false,
        val expectingScreenshot: Boolean = false,
        val textArrived: Boolean = false,
        val screenshotArrived: Boolean = false,
        val packageName: String? = null,
        val text: String = "",
        val screenshot: Bitmap? = null,
    ) {
        val complete: Boolean get() = (!expectingText || textArrived) && (!expectingScreenshot || screenshotArrived)
    }

    private val state = MutableStateFlow(State())

    /** The overlay opened and asked the system for screen data; the old capture no longer applies. */
    fun begin(text: Boolean, screenshot: Boolean) {
        state.value = State(expectingText = text, expectingScreenshot = screenshot)
    }

    /** One activity's view tree (the system may send several). [ownPackage]'s are skipped: they're Orbit itself. */
    fun onStructure(structure: AssistStructure?, ownPackage: String) {
        val pkg = structure?.activityComponent?.packageName
        val text = if (structure == null || pkg == ownPackage) "" else structureText(structure)
        state.update { s ->
            s.copy(
                textArrived = true,
                packageName = s.packageName ?: pkg?.takeIf { it != ownPackage && text.isNotEmpty() },
                text = listOf(s.text, text).filter { it.isNotEmpty() }.joinToString("\n"),
            )
        }
    }

    fun onScreenshot(bitmap: Bitmap?) = state.update { it.copy(screenshotArrived = true, screenshot = bitmap) }

    fun clear() {
        state.value = State()
    }

    /** The capture, once everything requested has arrived (or [timeoutMs] passed). */
    suspend fun await(timeoutMs: Long): State = withTimeoutOrNull(timeoutMs) { state.first { it.complete } } ?: state.value

    /** The visible text of a view tree, one line per view, skipping password fields. */
    private fun structureText(structure: AssistStructure): String {
        val lines = mutableListOf<String>()
        fun walk(node: AssistStructure.ViewNode) {
            if (node.visibility != View.VISIBLE) return
            val password = node.inputType and InputType.TYPE_MASK_VARIATION in PASSWORD_VARIATIONS
            val text = (node.text?.toString()?.takeUnless { password } ?: node.contentDescription?.toString())
                ?.replace(Regex("\\s+"), " ")?.trim()
            if (!text.isNullOrEmpty() && lines.lastOrNull() != text) lines += text
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) structure.getWindowNodeAt(i).rootViewNode?.let(::walk)
        return lines.joinToString("\n")
    }

    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    )
}

/** Lets the model look at the app behind the overlay. */
class ReadScreenTool(private val context: Context) : Tool {
    override val privateResult = true

    override val spec = ToolSpec(
        name = "read_screen",
        description = "Read what's on the user's screen: the app open behind Orbit, as text, and optionally a " +
            "screenshot. It goes to the model provider, so call it only when the user's request refers to the " +
            "screen (\"add this to my calendar\", \"what does this say\", \"reply to this\"); never just in case.",
        parameters = objectSchema(
            emptyList(),
            "screenshot" to booleanProperty(
                "Also send a screenshot: only for images, maps or layouts the text can't capture. It's included " +
                    "anyway when the app exposes no text.",
            ),
        ),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val capture = ScreenContext.await(timeoutMs = 2_000)
        val wantImage = args.boolean("screenshot") == true || capture.text.isBlank()
        val image = capture.screenshot?.takeIf { wantImage }?.let(::encode)
        if (capture.text.isBlank() && image == null) {
            EventLog.log("screen", "Nothing captured (expecting text=${capture.expectingText}, screenshot=${capture.expectingScreenshot})")
            return ToolOutcome(
                "Nothing from the screen is available. Either Orbit was opened from the lock screen or without an app " +
                    "behind it, or screen access is off: tell the user to turn on \"Use screen and app data\" in Android " +
                    "Settings > Apps > Default apps > Digital assistant app.",
            )
        }
        val app = capture.packageName?.let { appLabel(context, it) }
        EventLog.log("screen", "Read screen: app=$app, ${capture.text.length} chars, screenshot=${image != null}")
        val result = buildString {
            app?.let { append("App: $it\n") }
            if (capture.text.isNotBlank()) append("Text on screen:\n").append(capture.text.take(MAX_TEXT_CHARS))
            else append("The app exposes no text.")
            if (image != null) append("\n(A screenshot follows.)")
        }
        return ToolOutcome(result, images = listOfNotNull(image))
    }

    /** A JPEG no larger than [MAX_IMAGE_EDGE] on its long side: enough to read, cheap to send. */
    private fun encode(bitmap: Bitmap): ChatImage {
        val scale = MAX_IMAGE_EDGE.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return ChatImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private companion object {
        const val MAX_TEXT_CHARS = 8_000
        const val MAX_IMAGE_EDGE = 1_280
    }
}
