package com.hisbaan.orbit.tools

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hisbaan.orbit.diagnostics.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Google Maps' navigation screen, driven through Orbit's accessibility service. Maps has no API
 * or intent for searching along the route, adding a stop, reporting what's on the road or
 * changing route options mid-trip (Gemini does these through a private integration), so this
 * presses the buttons, found by the labels Maps gives screen readers (English). Search results
 * are drawn on the map without views; [MapsCallouts] reads them off a screenshot.
 *
 * Everything runs with Orbit's overlay moved aside ([onScreen]), so Maps can be seen and some
 * things tapped: the navigation sheet's header ignores clicks, and map labels have no views at
 * all. Never swipes, and presses Back only to close the
 * keyboard: Back otherwise leaves the navigation screen, and a swipe from the bottom edge sends
 * Maps home into picture-in-picture. Verified on Maps 26.44.
 *
 * Throws with a message for the model when something doesn't go as expected.
 */
internal object MapsNav {
    const val PACKAGE = "com.google.android.apps.maps"

    // Navigation screen. Content descriptions unless marked as text or a view id.
    private const val SEARCH_BUTTON = "nav_search_fab_button" // id
    private const val SEARCH_BOX = "search_omnibox_edit_text" // id
    private const val SEARCH_UP = "search_omnibox_menu_button" // id
    private const val SETTINGS_CLOSE = "terra_navigation_header_close_button" // id
    private const val LIST = "recycler_view" // id: the settings list, and the directions list
    private const val NEXT_STEP = "step_instruction_container" // id
    private const val OPEN_SHEET = "Activate to open step list"
    private const val CLOSE_SHEET = "Activate to close step list"
    private const val REPORT = "Add a report"
    private const val DISMISS = "Dismiss"
    private const val TIME_LEFT = "Time until arrival is "
    private const val DISTANCE_LEFT = "Distance remaining is "
    private const val CLEAR_RESULTS = "Clear results" // text
    private const val DIRECTIONS = "Directions" // text
    private const val SETTINGS = "Settings" // text
    private const val ARRIVAL_SIDE = "Destination will be on" // text
    private const val MORE = "More"

    /** Search along the route's shortcut chips, and what people call them. */
    private val CHIPS = mapOf(
        "Gas stations" to setOf("gas", "gas station", "fuel", "petrol", "gas bar"),
        "Restaurants" to setOf("restaurant", "food", "somewhere to eat"),
        "Coffee shops" to setOf("coffee", "coffee shop", "cafe", "café"),
        "Grocery stores" to setOf("grocery", "groceries", "grocery store", "supermarket"),
        "Rest stops" to setOf("rest stop", "rest area"),
        "Hotels" to setOf("hotel", "motel"),
    )

    /** The report sheet's buttons; the last two are under its More button. */
    val REPORTS = listOf("Crash", "Slowdown", "Police", "Construction", "Lane closure", "Object on road", "Flooded road")
    val MORE_REPORTS = listOf("Low visibility", "Unplowed road")

    /** Route options in Maps' navigation settings, which it applies to the trip under way. */
    val AVOID = listOf("Avoid tolls", "Avoid highways", "Avoid ferries")

    private const val OPEN_TIMEOUT_MS = 3_000L
    private const val RESULTS_TIMEOUT_MS = 8_000L
    private const val CLOSE_TIMEOUT_MS = 3_000L
    private const val NEW_TRIP_TIMEOUT_MS = 3_000L

    /** The map pans and zooms to fit search results; labels read before it stops are misplaced. */
    private const val SETTLE_MS = 1_500L

    /** One thing at a time on Maps' screen, even when the model calls two tools at once. */
    private val busy = Mutex()

    /** True while Maps shows its navigation notification, so its navigation screen exists. */
    fun isNavigating(): Boolean = try {
        MediaAccessService.connected?.activeNotifications.orEmpty().any {
            it.packageName == PACKAGE && it.notification.category == Notification.CATEGORY_NAVIGATION
        }
    } catch (_: SecurityException) {
        false
    }

    /**
     * Runs [block] on Maps' screen: one tool at a time, and with Orbit's overlay moved aside. The
     * overlay covers the whole screen and takes touches, and the system leaves windows under such
     * a window out of what an accessibility service sees: with it up, Maps isn't there at all.
     */
    private suspend fun <T> onScreen(service: ScreenControlService, block: suspend () -> T): T =
        busy.withLock { service.clearOfOverlay(block) }

    /** Searches along the route for [query] and reads the places Maps puts on the map, quickest first. */
    suspend fun search(context: Context, service: ScreenControlService, query: String): List<Callout> = onScreen(service) {
        try {
            showResults(context, service, query)
        } finally {
            // The map stays zoomed out on the results, not following the car, until they're cleared.
            clearResults(service)
        }
    }

    /** Searches again and taps [name]'s label, which adds it as a stop (a label has no button to press). */
    suspend fun addStop(context: Context, service: ScreenControlService, query: String, name: String, addedMinutes: Int?): Callout =
        onScreen(service) {
            val found = showResults(context, service, query)
            val stop = pick(found, name, addedMinutes)
            if (stop == null) {
                clearResults(service)
                error("Maps didn't show '$name' this time (it showed ${found.joinToString { it.name }.ifEmpty { "nothing" }})")
            }
            service.tap(stop.x.toFloat(), stop.y.toFloat())
            // A tap on a label adds the stop at once and closes the results.
            if (!service.awaitNoNode(PACKAGE, CLOSE_TIMEOUT_MS) { it.text?.toString() == CLEAR_RESULTS }) {
                clearResults(service)
                error("Maps didn't take the tap on '${stop.name}'")
            }
            EventLog.log("maps", "Added stop: ${EventLog.content(stop.describe())}")
            stop
        }

    /**
     * Says yes to Maps' "Exit navigation?", which it asks when a new trip starts over one under
     * way and takes as a no if nobody answers.
     */
    suspend fun confirmNewTrip(service: ScreenControlService) {
        service.awaitNode(PACKAGE, NEW_TRIP_TIMEOUT_MS) { it.text?.toString() == "Exit navigation?" } ?: return
        val yes = service.findNode(PACKAGE) { it.text?.toString() == "Yes" } ?: return
        service.clickRow(yes)
        EventLog.log("maps", "Replaced the trip under way")
    }

    /** [name] among [found], the one closest to [addedMinutes] if several match (three Circle Ks). */
    fun pick(found: List<Callout>, name: String, addedMinutes: Int?): Callout? =
        when (val match = NameMatch.find(found, name.removeSuffix("...").removeSuffix("…")) { it.name }) {
            is NameMatch.Result.One -> match.value
            is NameMatch.Result.Many -> match.values.minByOrNull { abs(it.addedMinutes - (addedMinutes ?: 0)) }
            NameMatch.Result.None -> null
        }

    /** Reports [label] (one of [REPORTS] or [MORE_REPORTS]) where the car is. */
    suspend fun report(context: Context, service: ScreenControlService, label: String) = onScreen(service) {
        open(context, service)
        val button = service.findNode(PACKAGE) { it.desc == REPORT && it.isClickable } ?: error("Maps' report button isn't showing")
        if (!service.click(button)) error("Maps' report button didn't respond")
        if (label in MORE_REPORTS) {
            val more = service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.desc == MORE && it.isClickable }
                ?: error("Maps' report sheet didn't open")
            service.click(more)
        }
        val choice = service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.desc == label && it.isClickable }
        if (choice == null) {
            dismiss(service)
            error("Maps didn't offer a '$label' report")
        }
        if (!service.click(choice) || !service.awaitNoNode(PACKAGE, CLOSE_TIMEOUT_MS) { it.desc == label && it.isClickable }) {
            error("Maps didn't take the report; the user can finish it on screen")
        }
        EventLog.log("maps", "Reported: $label")
    }

    /**
     * Sets [wanted] route options (by label, from [AVOID]) in Maps' navigation settings and
     * returns all of them as they end up.
     */
    suspend fun routeOptions(context: Context, service: ScreenControlService, wanted: Map<String, Boolean>): Map<String, Boolean> =
        onScreen(service) {
            open(context, service)
            setRouteOptions(service, wanted)
        }

    private suspend fun setRouteOptions(service: ScreenControlService, wanted: Map<String, Boolean>): Map<String, Boolean> =
        try {
            openSettings(service)
            // The list lays itself out again as the page slides in, undoing a scroll made before.
            val list = service.awaitView(PACKAGE, LIST, OPEN_TIMEOUT_MS) ?: error("Maps' navigation settings didn't open")
            delay(PAGE_SETTLE_MS)
            service.awaitStill(list)
            AVOID.associateWith { label ->
                // Route options are a few screens down the list, which has no rows for them until scrolled near.
                var before = emptyMap<String, Boolean>()
                for (attempt in 1..2) {
                    service.scrollTo(PACKAGE) { it.text?.toString() == label } ?: error("Maps' settings have no '$label'")
                    before = switches(service)
                    if (label in before) break
                }
                val was = before[label] ?: error("Maps' '$label' setting isn't on screen (only ${before.keys})")
                val want = wanted[label]
                if (want == null || was == want) return@associateWith was
                val row = settingRows(service)[label] ?: error("Maps' '$label' setting isn't on screen")
                service.click(row)
                delay(SWITCH_SETTLE_MS)
                // A list that was still moving can put another setting under the click: check that only this one changed.
                val after = switches(service)
                val changed = before.keys.filter { it in after && after[it] != before[it] }
                if (changed != listOf(label)) {
                    for (other in changed - label) settingRows(service)[other]?.let(service::click)
                    EventLog.log("maps", "Clicking '$label' changed $changed; put back")
                    error("Maps' settings moved under the click; '$label' wasn't changed")
                }
                EventLog.log("maps", "$label: $want")
                want
            }
        } finally {
            closeSettings(service, SETTINGS_CLOSE_TIMEOUT_MS)
            closeSheet(service)
        }

    /**
     * Opens Maps' navigation settings from the sheet's menu. Maps sometimes shows the page
     * invisible (its views all there and in place, but not drawn: a blank screen), seen on the
     * first open after Orbit's overlay moves aside; then this closes it and opens it again.
     */
    private suspend fun openSettings(service: ScreenControlService) {
        for (attempt in 1..2) {
            // The page is there, drawn or not, as soon as it opens; drawn, it shows within a second.
            openSheetItem(service, SETTINGS, drawnOnly = false) { it.viewIdResourceName == "$PACKAGE:id/$SETTINGS_CLOSE" }
            if (service.awaitView(PACKAGE, SETTINGS_CLOSE, DRAWN_TIMEOUT_MS) != null) return
            if (attempt == 2) fail(service, "Maps' settings opened blank twice")
            EventLog.log("maps", "Settings opened invisible; closing and opening again")
            closeSettings(service, OPEN_TIMEOUT_MS)
            closeSheet(service)
        }
    }

    /**
     * Closes Maps' navigation settings with a tap on its Close button, where it is even when
     * the page isn't drawn (a click on a view that isn't shown may not count). Waits up to [timeoutMs] for the page
     * (it may still be loading after an early failure).
     */
    private suspend fun closeSettings(service: ScreenControlService, timeoutMs: Long) {
        val close = service.awaitNode(PACKAGE, timeoutMs, visibleOnly = false) { it.viewIdResourceName == "$PACKAGE:id/$SETTINGS_CLOSE" }
        if (close == null) {
            EventLog.log("maps", "Closing settings: no Close button")
            return
        }
        tapMiddle(service, close)
        if (!service.awaitNoNode(PACKAGE, OPEN_TIMEOUT_MS, visibleOnly = false) { it.viewIdResourceName == "$PACKAGE:id/$SETTINGS_CLOSE" }) {
            EventLog.log("maps", "Closing settings: still open")
        }
    }

    data class Status(
        val timeLeft: String?,
        /** "3.7 km, estimated time of arrival is 10:43 a.m." */
        val distanceAndArrival: String?,
        val nextStep: String?,
        /** Stops on the way, in order, then the destination. */
        val stops: List<String>,
    )

    /** What the navigation screen and its directions list say about the trip. */
    suspend fun status(context: Context, service: ScreenControlService): Status = onScreen(service) {
        open(context, service)
        val time = service.findNode(PACKAGE) { it.desc?.startsWith(TIME_LEFT) == true }?.desc?.removePrefix(TIME_LEFT)
        val distance = service.findNode(PACKAGE) { it.desc?.startsWith(DISTANCE_LEFT) == true }?.desc?.removePrefix(DISTANCE_LEFT)
        val next = service.findView(PACKAGE, NEXT_STEP)?.desc
        val stops = try {
            openSheetItem(service, DIRECTIONS) { it.desc == CLOSE_SHEET }
            readStops(service)
        } finally {
            closeSheet(service)
        }
        Status(time, distance, next, stops)
    }

    /**
     * The directions list's stops. With stops, it starts with a summary: "Your location", then
     * a letter and a name per stop ("A", "Shell", "3 min", "B", "Sneaky Dee's"). Without, the
     * destination is the list's last row: its name and address, sometimes followed by
     * "Destination will be on the left".
     */
    private suspend fun readStops(service: ScreenControlService): List<String> {
        val list = service.awaitView(PACKAGE, LIST, OPEN_TIMEOUT_MS) ?: return emptyList()
        service.awaitStill(list)
        val summary = rows(list).let { all ->
            val start = all.indexOfFirst { it.first == "Your location" }
            if (start < 0) return@let emptyList()
            // Each leg's steps further down are headed by its letter again ("B", "To Sneaky Dee's"): first letters only.
            (start + 1 until all.size - 1)
                .filter { all[it].first.length == 1 && all[it].first[0] in 'A'..'Z' }
                .distinctBy { all[it].first }
                .map { all[it + 1].first }
        }
        if (summary.isNotEmpty()) return summary
        // The list has rows for what's near the screen only: scroll to its end.
        for (page in 1..MAX_LIST_PAGES) {
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            service.awaitStill(list)
        }
        val last = rows(list).dropLastWhile { it.first.startsWith(ARRIVAL_SIDE) }
        val (name, address) = when {
            last.isEmpty() -> return emptyList()
            // An address row has no description of its own; a place name or a step does.
            last.size >= 2 && last.last().second == null -> last[last.size - 2].first to last.last().first
            else -> last.last().first to null
        }
        return listOf(listOfNotNull(name, address).joinToString(", "))
    }

    /** [list]'s text rows in order, read fresh: text, and description when there is one. */
    private fun rows(list: AccessibilityNodeInfo): List<Pair<String, String?>> {
        list.refresh()
        val found = mutableListOf<Pair<String, String?>>()
        fun walk(node: AccessibilityNodeInfo) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { found += it to node.desc?.takeIf(String::isNotBlank) }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(list)
        return found
    }

    /**
     * Brings Maps' navigation screen to the front (under Orbit's overlay, if it's up) as it is
     * while driving, closing a search, the step list, a sheet or settings left open over it.
     */
    private suspend fun open(context: Context, service: ScreenControlService) {
        if (!isNavigating()) error("Maps isn't navigating")
        val launched = service.topApp() != PACKAGE
        if (launched) {
            val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: error("Google Maps isn't installed")
            // From the service: a bound accessibility service may start activities from the background.
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        repeat(4) {
            service.hideKeyboard()
            if (service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.desc == OPEN_SHEET } != null && isPlain(service)) {
                // Coming back from picture-in-picture, the screen is still growing: taps would miss.
                if (launched) delay(LAUNCH_SETTLE_MS)
                return
            }
            if (service.findNode(PACKAGE, visibleOnly = false) { it.viewIdResourceName == "$PACKAGE:id/$SETTINGS_CLOSE" } != null) {
                EventLog.log("maps", "Closing settings left over the navigation screen")
                closeSettings(service, OPEN_TIMEOUT_MS)
                return@repeat
            }
            val cover = service.findNode(PACKAGE) { node ->
                node.desc == DISMISS || node.desc == CLOSE_SHEET || node.text?.toString() == CLEAR_RESULTS ||
                    node.viewIdResourceName == "$PACKAGE:id/$SEARCH_UP"
            } ?: return@repeat
            EventLog.log("maps", "Closing what's over the navigation screen: ${cover.desc ?: cover.text ?: cover.viewIdResourceName}")
            if (cover.desc == CLOSE_SHEET) closeSheet(service) else service.clickRow(cover)
            delay(CLOSE_SETTLE_MS)
        }
        fail(service, "Maps' navigation screen isn't showing on the phone (it may be on a car's screen)")
    }

    /** Nothing open over the navigation screen: no search, results, sheet or step list. */
    private fun isPlain(service: ScreenControlService) =
        service.findView(PACKAGE, SEARCH_BUTTON) != null &&
            service.findNode(PACKAGE) { it.desc == DISMISS || it.desc == CLOSE_SHEET || it.text?.toString() == CLEAR_RESULTS } == null &&
            service.findNode(PACKAGE, visibleOnly = false) { it.viewIdResourceName == "$PACKAGE:id/$SETTINGS_CLOSE" } == null

    /** Runs a search along the route and leaves its results on the map. */
    private suspend fun showResults(context: Context, service: ScreenControlService, query: String): List<Callout> {
        open(context, service)
        val button = service.findView(PACKAGE, SEARCH_BUTTON) ?: error("Maps' search along the route button isn't showing")
        service.click(button)
        val box = service.awaitView(PACKAGE, SEARCH_BOX, OPEN_TIMEOUT_MS) ?: error("Maps' search along the route didn't open")
        val chip = chipFor(query)?.let { label -> service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.text?.toString() == label } }
        if (chip != null) {
            service.clickRow(chip)
        } else {
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
            if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) error("Maps' search box didn't take the text")
            box.refresh()
            if (!box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) error("Maps didn't run the search")
        }
        // The keyboard can stay up over the results (and their Clear button) after a search.
        val shown = service.awaitNode(PACKAGE, RESULTS_TIMEOUT_MS, visibleOnly = false) { it.text?.toString() == CLEAR_RESULTS }
        service.hideKeyboard()
        if (shown == null) {
            service.findView(PACKAGE, SEARCH_UP)?.let(service::click)
            error("Maps found no '$query' along the route")
        }
        delay(SETTLE_MS)
        val found = readMap(service).ifEmpty {
            delay(SETTLE_MS)
            readMap(service)
        }
        EventLog.log("maps", "Along the route for ${EventLog.content(query)}: ${EventLog.content(found.joinToString { it.describe() })}")
        return found
    }

    /** The labels on the map, in screen coordinates. */
    private suspend fun readMap(service: ScreenControlService): List<Callout> {
        val (shot, window) = service.screenshot(PACKAGE) ?: error("Couldn't take a screenshot of the map")
        val lines = try {
            TextReader.lines(shot)
        } finally {
            shot.recycle()
        }
        val found = MapsCallouts.find(lines)
        if (found.isEmpty()) {
            // What text recognition saw, to tell a search with no results from labels it couldn't read.
            EventLog.log("maps", "No places read off the map: ${EventLog.content(lines.joinToString(" | ") { "${it.text} ${it.left},${it.top},${it.right},${it.bottom}" })}")
        }
        return found.map { it.copy(x = it.x + window.left, y = it.y + window.top) }
    }

    private suspend fun clearResults(service: ScreenControlService) {
        val clear = service.findNode(PACKAGE) { it.text?.toString() == CLEAR_RESULTS } ?: return
        service.clickRow(clear)
        service.awaitNoNode(PACKAGE, CLOSE_TIMEOUT_MS) { it.text?.toString() == CLEAR_RESULTS }
    }

    /**
     * Opens the navigation sheet and its menu's [item], which has [opened] once it shows (on
     * screen, or with [drawnOnly] false just there). The
     * sheet's header ignores clicks and opens to a touch only, so this taps it. (Clicking the
     * menu's rows while the sheet is down, when Maps has them, opens the item too, but Maps'
     * settings can take over a second to show that way, and the sheet's gone by then.)
     */
    private suspend fun openSheetItem(
        service: ScreenControlService,
        item: String,
        drawnOnly: Boolean = true,
        opened: (AccessibilityNodeInfo) -> Boolean,
    ) {
        // Once more if the first tap is lost (the map was still moving).
        val sheetOpen = (1..2).any {
            val header = service.findNode(PACKAGE) { it.desc == OPEN_SHEET } ?: fail(service, "Maps' navigation sheet isn't showing")
            tapMiddle(service, header)
            service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.desc == CLOSE_SHEET } != null
        }
        if (!sheetOpen) fail(service, "Maps' navigation sheet didn't open")
        val row = service.scrollTo(PACKAGE) { it.text?.toString() == item } ?: fail(service, "Maps' navigation menu has no '$item'")
        if (!service.clickRow(row) || service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS, visibleOnly = drawnOnly, matches = opened) == null) {
            fail(service, "Maps' '$item' didn't open")
        }
    }

    /** Logs what's on screen, then throws [message]: a step's failure, with what it found instead. */
    private fun fail(service: ScreenControlService, message: String): Nothing {
        EventLog.log("maps", "$message. On screen: ${EventLog.content(service.outline(PACKAGE))}")
        error(message)
    }

    /** Closes the navigation sheet, by touch like [openSheetItem]. */
    private suspend fun closeSheet(service: ScreenControlService) {
        val header = service.findNode(PACKAGE) { it.desc == CLOSE_SHEET } ?: return
        tapMiddle(service, header)
        service.awaitNode(PACKAGE, OPEN_TIMEOUT_MS) { it.desc == OPEN_SHEET }
    }

    private suspend fun tapMiddle(service: ScreenControlService, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        service.tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun dismiss(service: ScreenControlService) {
        service.findNode(PACKAGE) { it.desc == DISMISS && it.isClickable }?.let(service::click)
    }

    private fun chipFor(query: String): String? {
        val said = query.trim().lowercase().removeSuffix("s")
        return CHIPS.entries.firstOrNull { (label, names) -> said == label.lowercase().removeSuffix("s") || said in names }?.key
    }

    /**
     * The settings rows on screen with a switch, by their title, from one pass over the tree so
     * each row's title and switch belong together.
     */
    private fun settingRows(service: ScreenControlService): Map<String, AccessibilityNodeInfo> =
        service.findNodes(PACKAGE) { it.isClickable && it.childCount > 0 }.mapNotNull { row ->
            // Switch rows are AndroidX preferences (android:id/title); Maps' own rows use its id.
            val title = firstDescendant(row) { it.viewIdResourceName?.endsWith(":id/title") == true }?.text?.toString()
            if (title == null || firstCheckable(row) == null) null else title to row
        }.toMap()

    private fun switches(service: ScreenControlService): Map<String, Boolean> =
        settingRows(service).mapValues { (_, row) -> firstCheckable(row)!!.isChecked }

    private fun firstCheckable(node: AccessibilityNodeInfo) = firstDescendant(node) { it.isCheckable }

    private fun firstDescendant(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { firstDescendant(it, matches) }?.let { return it }
        return null
    }

    private val AccessibilityNodeInfo.desc get() = contentDescription?.toString()

    private const val CLOSE_SETTLE_MS = 600L
    private const val LAUNCH_SETTLE_MS = 1_000L
    private const val MAX_LIST_PAGES = 20
    private const val PAGE_SETTLE_MS = 500L
    private const val SWITCH_SETTLE_MS = 400L
    private const val SETTINGS_CLOSE_TIMEOUT_MS = 2_000L
    private const val DRAWN_TIMEOUT_MS = 1_000L
}

/** On-device text recognition (ML Kit, its model kept by Play services). */
internal object TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun lines(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { done ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                done.resume(
                    text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                    },
                )
            }
            .addOnFailureListener { done.resumeWithException(IllegalStateException("Text recognition failed: ${it.message}", it)) }
    }
}
