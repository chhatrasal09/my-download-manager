package com.app.mydownloadmanager

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.random.Random

@Entity(
    tableName = "download_item_table",
    indices = [Index(value = ["download_url"], unique = true)]
)
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "download_url") val downloadUrl: String,
    @ColumnInfo(name = "download_status") var downloadStatus: Int = DOWNLOAD_STATUS_PENDING
) {
    companion object {
        const val DOWNLOAD_STATUS_PENDING = 0x000
        const val DOWNLOAD_STATUS_STARTED = 0x001
        const val DOWNLOAD_STATUS_COMPLETED = 0x002
        const val DOWNLOAD_STATUS_FAILED = 0x003
    }
}