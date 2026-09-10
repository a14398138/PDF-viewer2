package com.example.renamer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.PdfItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RenameHistoryTest {
    @Test fun renamePreservesNotesPagesAndCacheAndDoesNotResurrectOldUri() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.pdfHistoryDao()
            val oldUri = "content://papers/document/old"
            val newUri = "content://papers/document/new"
            dao.insert(PdfItem(uriString = oldUri, fileName = "old.pdf",
                noteContent = "Important note", lastOpenedPage = 7,
                filePath = "/cache/paper.pdf", lastViewedTimestamp = 123L))
            val original = dao.getByUriSync(oldUri)!!
            dao.updateRenamedDocument(original.id, newUri, "new.pdf")
            dao.updateThumbnail(oldUri, "/cache/late-thumbnail.png")
            assertNull(dao.getByUriSync(oldUri))
            val updated = dao.getByUriSync(newUri)!!
            assertEquals(original.copy(uriString = newUri, fileName = "new.pdf"), updated)
            assertEquals(1, dao.getAllHistorySync().size)
        } finally {
            db.close()
        }
    }
}
