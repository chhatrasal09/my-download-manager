package com.app.mydownloadmanager

import androidx.room.*

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_item_table")
    suspend fun fetchAllDownloadItems(): List<DownloadItem>

    @Query("SELECT * FROM download_item_table WHERE download_status == ${DownloadItem.DOWNLOAD_STATUS_PENDING} OR download_status == ${DownloadItem.DOWNLOAD_STATUS_FAILED}")
    suspend fun fetchRemainingDownloadItems(): List<DownloadItem>

    @Query("SELECT COUNT(*) FROM download_item_table WHERE download_status == ${DownloadItem.DOWNLOAD_STATUS_PENDING} OR download_status == ${DownloadItem.DOWNLOAD_STATUS_FAILED}")
    suspend fun fetchAvailableUrlToDownloadCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(vararg items: DownloadItem): Array<Long>

    @Update
    suspend fun updateDownloadStatus(vararg items: DownloadItem)
}