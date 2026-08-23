package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfHistoryDao {
    @Query("SELECT * FROM pdf_history ORDER BY lastViewedTimestamp DESC")
    fun getAllHistory(): Flow<List<PdfItem>>

    @Query("SELECT * FROM pdf_history WHERE uriString = :uriString LIMIT 1")
    fun getByUri(uriString: String): Flow<PdfItem?>

    @Query("SELECT * FROM pdf_history WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUriSync(uriString: String): PdfItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PdfItem): Long

    @Update
    suspend fun update(item: PdfItem)

    @Query("UPDATE pdf_history SET noteContent = :note WHERE uriString = :uriString")
    suspend fun updateNote(uriString: String, note: String)

    @Query("UPDATE pdf_history SET fileName = :fileName WHERE uriString = :uriString")
    suspend fun updateFileName(uriString: String, fileName: String)

    @Query("UPDATE pdf_history SET lastOpenedPage = :lastPage, lastViewedTimestamp = :timestamp WHERE uriString = :uriString")
    suspend fun updateLastPage(uriString: String, lastPage: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM pdf_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pdf_history WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM pdf_history")
    suspend fun clearAll()
}
