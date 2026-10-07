package com.rork.rgdsartworkprep.ui.screens

import com.rork.rgdsartworkprep.model.GameSystem

/**
 * The platform chips over the Artwork gallery: one per detected system that has at
 * least one cover, so there are never dead chips. Pure, so ordering and filtering run
 * on the JVM.
 */
internal object ArtworkPlatforms {

    /** Key for covers whose game has no detected system. */
    const val OTHER_KEY: String = "other"

    data class Chip(val key: String, val label: String, val count: Int)

    /**
     * The RG DS is a dual-screen handheld, so its two native platforms lead, then the
     * rest by how many covers they have, then by name. Undetected games come last.
     */
    private val LEADING: List<String> = listOf("n3ds", "nds")

    fun keyOf(system: GameSystem?): String = system?.key ?: OTHER_KEY

    fun <T> chips(items: List<T>, systemOf: (T) -> GameSystem?): List<Chip> {
        val groups = items.groupBy { keyOf(systemOf(it)) }
        return groups.map { (key, group) ->
            Chip(key = key, label = systemOf(group.first())?.shortName ?: "Other", count = group.size)
        }.sortedWith(
            compareBy<Chip> { it.key == OTHER_KEY }
                .thenBy { LEADING.indexOf(it.key).let { index -> if (index < 0) LEADING.size else index } }
                .thenByDescending { it.count }
                .thenBy { it.label.lowercase() },
        )
    }

    /** [items] for one platform, or all of them when [selectedKey] is null. */
    fun <T> filter(items: List<T>, selectedKey: String?, systemOf: (T) -> GameSystem?): List<T> =
        if (selectedKey == null) items else items.filter { keyOf(systemOf(it)) == selectedKey }

    /**
     * The selection to apply: a platform whose covers are all gone after a rescan falls
     * back to every platform rather than showing an empty gallery.
     */
    fun effectiveSelection(selectedKey: String?, chips: List<Chip>): String? =
        selectedKey?.takeIf { key -> chips.any { it.key == key } }
}
