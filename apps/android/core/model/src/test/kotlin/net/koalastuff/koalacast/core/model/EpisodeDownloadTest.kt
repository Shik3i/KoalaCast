package net.koalastuff.koalacast.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDownloadTest {
    @Test
    fun `progress is bounded and safe when total is unknown`() {
        assertEquals(0, download(bytes = 10, total = 0).progressPercent)
        assertEquals(25, download(bytes = 25, total = 100).progressPercent)
        assertEquals(100, download(bytes = 150, total = 100).progressPercent)
    }

    private fun download(bytes: Long, total: Long) = EpisodeDownload(
        episodeId = "episode",
        track = Track("episode", "podcast", "Title", "Show", "", "https://audio", 1),
        state = DownloadState.DOWNLOADING,
        bytesDownloaded = bytes,
        totalBytes = total,
        localPath = null,
        error = null,
        createdAtMs = 0,
        updatedAtMs = 0,
    )
}
