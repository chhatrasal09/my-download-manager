package com.app.mydownloadmanager

import android.content.Context
import kotlinx.coroutines.Job

interface DownloadManagr {

    suspend fun startDownload(context: Context, url: String)
}