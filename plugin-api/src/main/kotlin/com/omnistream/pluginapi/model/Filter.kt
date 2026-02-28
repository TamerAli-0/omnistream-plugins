package com.omnistream.pluginapi.model

/**
 * Type-safe filter system for source search UI.
 *
 * Based on Mihon source-api Filter.kt (proven pattern).
 * Sources implement getFilters(): FilterList returning their supported filter types.
 *
 * Plugin authors create concrete subclasses of the abstract inner classes:
 *   class GenreFilter : Filter.Select<String>("Genre", arrayOf("Action", "Comedy", ...))
 *
 * The default implementation returns FilterList() (empty), so sources only implement
 * what they actually support.
 */
sealed class Filter<T>(val name: String, var state: T) {

    /** Non-interactive label/heading row in the filter sheet. */
    class Header(name: String) : Filter<Any>(name, 0)

    /** Visual separator in the filter sheet. */
    class Separator(name: String = "") : Filter<Any>(name, 0)

    /**
     * Dropdown/spinner with a fixed list of string options.
     * state = index of selected option.
     */
    abstract class Select<V>(name: String, val values: Array<V>, state: Int = 0)
        : Filter<Int>(name, state)

    /**
     * Free-text input.
     */
    abstract class Text(name: String, state: String = "")
        : Filter<String>(name, state)

    /**
     * Simple boolean toggle (checked / unchecked).
     */
    abstract class CheckBox(name: String, state: Boolean = false)
        : Filter<Boolean>(name, state)

    /**
     * Three-state toggle: ignore / include / exclude.
     * Useful for genre inclusion/exclusion.
     */
    abstract class TriState(name: String, state: Int = STATE_IGNORE)
        : Filter<Int>(name, state) {

        fun isIgnored() = state == STATE_IGNORE
        fun isIncluded() = state == STATE_INCLUDE
        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    /**
     * Group of sub-filters (e.g., a list of genre checkboxes).
     */
    abstract class Group<V>(name: String, state: List<V>)
        : Filter<List<V>>(name, state)

    /**
     * Sort options with direction (ascending/descending).
     */
    abstract class Sort(name: String, val values: Array<String>, state: Selection? = null)
        : Filter<Sort.Selection?>(name, state) {

        data class Selection(val index: Int, val ascending: Boolean)
    }
}

/**
 * Ordered list of filters returned by a source's getFilters() method.
 * Implements List<Filter<*>> by delegation for easy iteration.
 */
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {
    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())
}
