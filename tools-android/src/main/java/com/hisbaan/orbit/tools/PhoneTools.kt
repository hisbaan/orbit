package com.hisbaan.orbit.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import com.hisbaan.orbit.agent.AfterTurnAction
import com.hisbaan.orbit.agent.Tool
import com.hisbaan.orbit.agent.ToolOutcome
import com.hisbaan.orbit.agent.boolean
import com.hisbaan.orbit.agent.booleanProperty
import com.hisbaan.orbit.agent.objectSchema
import com.hisbaan.orbit.agent.requireString
import com.hisbaan.orbit.agent.string
import com.hisbaan.orbit.agent.stringProperty
import com.hisbaan.orbit.providers.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Calls a contact or number. Only dials on a single unambiguous match; otherwise the model gets
 * the candidates and asks. The first call only resolves the number: the model reads it back
 * and calls again with `confirmed` once the user says yes, so a misheard name never dials.
 * After turn: the call needs the SCO link Orbit is holding.
 */
class CallContactTool(private val context: Context) : Tool {
    private val numberTypes = mapOf("mobile" to Phone.TYPE_MOBILE, "home" to Phone.TYPE_HOME, "work" to Phone.TYPE_WORK)

    override val spec = ToolSpec(
        name = "call_contact",
        description = "Phone a contact by name, or a phone number. First call it without confirmed: it looks up " +
            "who would be called, and you ask the user to confirm. Call again with confirmed=true only after they agree. " +
            "If several contacts match, you get their names back: ask the user which one. The call starts after you finish speaking.",
        parameters = objectSchema(
            listOf("who"),
            "who" to stringProperty("Contact name as the user said it, or a phone number"),
            "number_type" to stringProperty("Which of the contact's numbers, if the user said", numberTypes.keys.toList()),
            "confirmed" to booleanProperty("True only once the user has confirmed this exact call"),
        ),
    )

    private data class Candidate(val contactId: Long, val name: String, val number: String, val type: Int, val primary: Boolean)

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val who = args.requireString("who")
        val confirmed = args.boolean("confirmed") == true
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && digits.length >= who.count { !it.isWhitespace() } - 2) {
            return if (confirmed) dial(digits, digits) else askToConfirm("the number ${digits.toList().joinToString(" ")}")
        }
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return ToolOutcome("Error: Orbit doesn't have contacts permission. Tell the user to grant it in Orbit's settings.")
        }

        val matches = query(who)
        val byContact = matches.groupBy { it.contactId }
        val exact = byContact.filterValues { list -> list.first().name.equals(who, ignoreCase = true) }
        val chosen = when {
            exact.size == 1 -> exact.values.single()
            byContact.size == 1 -> byContact.values.single()
            byContact.isEmpty() -> return ToolOutcome("No contact matches '$who'.")
            else -> return ToolOutcome(
                "Several contacts match '$who': ${byContact.values.take(5).joinToString { it.first().name }}. Ask which one.",
            )
        }
        val wantedType = numberTypes[args.string("number_type")]
        val number = chosen.firstOrNull { wantedType != null && it.type == wantedType }
            ?: chosen.firstOrNull { it.primary }
            ?: chosen.firstOrNull { it.type == Phone.TYPE_MOBILE }
            ?: chosen.first()
        if (!confirmed) {
            val type = numberTypes.entries.firstOrNull { it.value == number.type }?.key
            val numbers = chosen.map { it.number }.distinct().size
            return askToConfirm(number.name + if (type != null && numbers > 1) " on $type" else "")
        }
        return dial(number.number, number.name)
    }

    private fun askToConfirm(target: String) = ToolOutcome(
        "Not called yet. Ask the user to confirm in a short question, e.g. \"Call $target?\". " +
            "If they agree, call call_contact again with the same arguments and confirmed=true.",
    )

    private fun dial(number: String, label: String): ToolOutcome {
        val canCall = granted(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ToolOutcome(
            result = if (canCall) {
                "Queued: calling $label after you finish speaking."
            } else {
                "Queued: the dialer will open with $label's number (no call permission, so the user must press call)."
            },
            afterTurn = AfterTurnAction("call $label", needsUnlock = !canCall) { context.startActivity(intent) },
        )
    }

    private fun query(name: String): List<Candidate> {
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY)
        val result = mutableListOf<Candidate>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                result += Candidate(
                    contactId = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    number = c.getString(2).orEmpty(),
                    type = c.getInt(3),
                    primary = c.getInt(4) != 0,
                )
            }
        }
        return result
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Opens an installed app by name. After turn. */
class OpenAppTool(private val context: Context) : Tool {
    override val spec = ToolSpec(
        name = "open_app",
        description = "Open an installed app by name. Opens after you finish speaking.",
        parameters = objectSchema(listOf("name"), "name" to stringProperty("App name, e.g. 'Spotify'")),
    )

    override suspend fun invoke(args: JsonObject): ToolOutcome {
        val name = args.requireString("name")
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?: apps.filter { it.first.contains(name, ignoreCase = true) }.singleOrNull()
            ?: return ToolOutcome(
                apps.filter { it.first.contains(name, ignoreCase = true) }.takeIf { it.isNotEmpty() }
                    ?.let { "Several apps match '$name': ${it.take(5).joinToString { a -> a.first }}. Ask which one." }
                    ?: "No installed app is called '$name'.",
            )
        val launch = pm.getLaunchIntentForPackage(match.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ToolOutcome("Error: ${match.first} can't be launched.")
        return ToolOutcome(
            result = "Queued: ${match.first} will open after you finish speaking.",
            afterTurn = AfterTurnAction("open ${match.first}", needsUnlock = true) { context.startActivity(launch) },
        )
    }
}
