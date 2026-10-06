package com.hisbaan.orbit.homeassistant

/**
 * Turns what the model asks to control (entity ids, device names as the user said them, an
 * area, "all") into entities. Orbit resolves targets itself instead of relying on HA's Assist,
 * which only sees entities exposed to it and reports success while skipping the rest.
 */
object HaTargets {
    sealed interface Resolution {
        data class Found(val entities: List<HaEntity>, val skipped: List<HaEntity>) : Resolution
        data class Problem(val message: String) : Resolution
    }

    /**
     * [names]: entity ids or friendly names ("all" for every entity of [domain]). [area]: an
     * area name, roughly as said ("the living room"). [domain] narrows names and is required
     * with an area or "all". Unavailable entities picked up by an area or "all" are skipped.
     */
    fun resolve(
        entities: List<HaEntity>,
        areas: Map<String, String>,
        names: List<String>,
        area: String?,
        domain: String?,
    ): Resolution {
        if (names.isEmpty() && area == null) return Resolution.Problem("Give entities or an area.")
        val broad = area != null || names.any { it.equals("all", ignoreCase = true) }
        if (broad && domain == null) {
            return Resolution.Problem("With an area or 'all', give the service as domain.service, e.g. light.turn_off.")
        }
        var pool = entities.filter { domain == null || it.domain == domain }
        if (area != null) {
            val areaName = matchArea(areas.values.toSet(), area)
                ?: return Resolution.Problem("No area called '$area'. Areas: ${areas.values.toSortedSet().joinToString()}.")
            pool = pool.filter { areas[it.entityId] == areaName }
            if (pool.isEmpty()) return Resolution.Problem("No $domain entities in $areaName.")
        }
        val picked = if (names.isEmpty() || names.any { it.equals("all", ignoreCase = true) }) {
            pool
        } else {
            names.map { name ->
                when (val match = matchName(pool, entities, areas, name, domain)) {
                    is NameMatch.One -> match.entity
                    is NameMatch.None -> return Resolution.Problem(
                        "Nothing called '$name'" + (domain?.let { " in $it" } ?: "") + ". Use home_states to find it.",
                    )
                    is NameMatch.Many -> return Resolution.Problem(
                        "'$name' matches several: " + match.entities.take(8).joinToString { "${it.entityId} \"${it.name}\"" } +
                            ". Give entity ids.",
                    )
                }
            }.distinctBy { it.entityId }
        }
        val (usable, skipped) = if (broad) picked.partition { it.state != "unavailable" } else picked to emptyList()
        if (usable.isEmpty()) return Resolution.Problem("Everything matched is unavailable: ${skipped.joinToString { it.name }}.")
        return Resolution.Found(usable, skipped)
    }

    private sealed interface NameMatch {
        data class One(val entity: HaEntity) : NameMatch
        data class Many(val entities: List<HaEntity>) : NameMatch
        data object None : NameMatch
    }

    private fun matchName(pool: List<HaEntity>, all: List<HaEntity>, areas: Map<String, String>, name: String, domain: String?): NameMatch {
        // An exact entity id, even outside the pool (the model may name another domain's entity).
        all.firstOrNull { it.entityId.equals(name, ignoreCase = true) }?.let { return NameMatch.One(it) }
        val wanted = normalize(name)
        // Without a domain, prefer things one controls over sensors and config entities.
        val candidates = if (domain == null) pool.filter { it.domain in HaEntity.CONTROLLABLE } else pool
        candidates.filter { normalize(it.name) == wanted }.let { if (it.size == 1) return NameMatch.One(it.single()) else if (it.size > 1) return NameMatch.Many(it) }
        val words = wanted.split(' ').filter { it.isNotEmpty() }
        val loose = candidates.filter { e ->
            val haystack = normalize("${e.entityId.substringAfter('.').replace('_', ' ')} ${e.name} ${areas[e.entityId].orEmpty()}")
            words.all { it in haystack }
        }
        return when (loose.size) {
            0 -> NameMatch.None
            1 -> NameMatch.One(loose.single())
            else -> NameMatch.Many(loose)
        }
    }

    fun matchArea(areaNames: Set<String>, said: String): String? {
        val wanted = normalize(said)
        areaNames.firstOrNull { normalize(it) == wanted }?.let { return it }
        return areaNames.filter { normalize(it).let { n -> wanted in n || n in wanted } }.singleOrNull()
    }

    private fun normalize(text: String) =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().removePrefix("the ").trim()

    /** The state a service puts an entity in, when there is one, so Orbit needn't wait if it's already there. */
    fun targetState(service: String): String? = when (service) {
        "turn_on" -> "on"
        "turn_off" -> "off"
        "lock" -> "locked"
        "unlock" -> "unlocked"
        "open_cover", "open_valve" -> "open"
        "close_cover", "close_valve" -> "closed"
        else -> null
    }
}
