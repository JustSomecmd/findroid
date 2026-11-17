package dev.jdtech.jellyfin.models

import java.util.UUID

sealed class HomeItem {
    data object OfflineCard : HomeItem() {
        override val id: UUID = UUID.fromString("dbfef8a9-7ff0-4c36-9e36-81dfd65fdd46")
    }

    data class Suggestions(override val id: UUID, val items: List<FindroidItem>) : HomeItem()

    data class Section(val homeSection: HomeSection) : HomeItem() {
        override val id = homeSection.id
    }

    /**
     * A library's newest items. With [episodeThumbnails], episodes are shown with their own
     * thumbnail instead of their show's poster.
     */
    data class ViewItem(val view: View, val episodeThumbnails: Boolean = false) : HomeItem() {
        override val id = view.id
    }

    abstract val id: UUID
}
