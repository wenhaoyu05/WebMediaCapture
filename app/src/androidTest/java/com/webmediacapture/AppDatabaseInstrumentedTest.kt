package com.webmediacapture

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.webmediacapture.database.AppDatabase
import com.webmediacapture.database.DownloadEntity
import com.webmediacapture.database.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseInstrumentedTest {
    @Test
    fun downloadQueueStatePersistsLocally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.downloads().upsert(DownloadEntity(
                id = "test",
                mediaUrl = "https://example.test/video.mp4",
                pageUrl = "https://example.test",
                title = "test",
                type = "DIRECT",
            ))
            database.downloads().updateState("test", DownloadState.DOWNLOADING, 50, 100, null, null)
            val stored = database.downloads().get("test")
            assertEquals(DownloadState.DOWNLOADING, stored?.state)
            assertEquals(50L, stored?.bytesDownloaded)
        } finally {
            database.close()
        }
    }

    @Test
    fun historyClearAndDeleteUrl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val history = database.history()
            history.insert(com.webmediacapture.database.HistoryEntity(url = "https://a.test", title = "a"))
            history.insert(com.webmediacapture.database.HistoryEntity(url = "https://a.test", title = "a2"))
            history.insert(com.webmediacapture.database.HistoryEntity(url = "https://b.test", title = "b"))
            history.deleteUrl("https://a.test")
            assertEquals(listOf("https://b.test"), history.recent().map { it.url })
            history.clear()
            assertEquals(emptyList<String>(), history.recent().map { it.url })
        } finally {
            database.close()
        }
    }
}
