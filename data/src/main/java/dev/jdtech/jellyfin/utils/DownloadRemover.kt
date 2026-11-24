package dev.jdtech.jellyfin.utils

import java.util.UUID

/**
 * Takes a downloaded movie or episode off the device, as the player is asked to once it's played.
 */
interface DownloadRemover {
    /**
     * Deletes the download of [itemId] with everything kept with it, as removing it from its page
     * does. False when it isn't downloaded. Carries on when the caller goes away.
     */
    suspend fun deleteDownload(itemId: UUID): Boolean
}
