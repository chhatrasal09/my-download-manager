package com.app.mydownloadmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadManagrImpl : DownloadManagr {

    private lateinit var mDatabaseDao: DownloadDao

    override suspend fun startDownload(context: Context, url: String) =
        withContext(Dispatchers.IO) {
            mDatabaseDao = AppDatabase.getDatabase(context.applicationContext).downloadDao()
            val inserted = mDatabaseDao.addItem(
                DownloadItem(
                    downloadUrl = url,
                    downloadStatus = DownloadItem.DOWNLOAD_STATUS_PENDING
                )
            )
            if (inserted.isEmpty())
                return@withContext
            val work = OneTimeWorkRequestBuilder<DownloaderService>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            withContext(Dispatchers.Main) {
                WorkManager.getInstance(context.applicationContext).enqueue(work)
            }
            return@withContext
        }
}