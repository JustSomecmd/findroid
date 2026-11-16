package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class FindroidPlaylist(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = true,
    override val canDownload: Boolean = false,
    override val sources: List<FindroidSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int? = null,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
    val itemCount: Int? = null,
) : FindroidItem

fun BaseItemDto.toFindroidPlaylist(jellyfinRepository: JellyfinRepository): FindroidPlaylist {
    return FindroidPlaylist(
        id = id,
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        runtimeTicks = runTimeTicks ?: 0L,
        images = toFindroidImages(jellyfinRepository),
        itemCount = childCount,
    )
}
