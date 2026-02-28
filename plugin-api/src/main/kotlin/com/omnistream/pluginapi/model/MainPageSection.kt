package com.omnistream.pluginapi.model

/**
 * Static descriptor for a home page section.
 *
 * Sources declare their sections in mainPageSections: List<MainPageSection>.
 * This list is static — declared once, never changes at runtime.
 * The app calls getSection(section, page) lazily to fetch content for each section.
 *
 * Replaces HomeSection which conflated the section identity with its paginated content.
 *
 * @property name Display name shown in the UI (e.g., "Trending", "Recent Episodes")
 * @property data Source-internal identifier used by getSection() to fetch this section
 *                (e.g., a URL path segment like "trending" or "ongoing-anime")
 * @property horizontalImages True if this section's items should display in landscape aspect ratio
 */
data class MainPageSection(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)
