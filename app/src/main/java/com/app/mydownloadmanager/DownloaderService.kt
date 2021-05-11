package com.app.mydownloadmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.NonNull
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class DownloaderService(context: Context, workerParams: WorkerParameters) : CoroutineWorker(
    context,
    workerParams
), CoroutineScope {

    /**
     * The coroutine context on which [doWork] will run. By default, this is [Dispatchers.Default].
     */
    override val coroutineContext: CoroutineDispatcher
        get() = Dispatchers.IO

    private val mDatabaseDao: DownloadDao by lazy {
        AppDatabase.getDatabase(applicationContext).downloadDao()
    }

    private val mNotificationManagerCompat by lazy {
        NotificationManagerCompat.from(applicationContext)
    }

    private var mTotalDownloadSize = AtomicLong(0)
    private var mDownloadingFile: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    private var mFailedDownloads: MutableSet<String> = mutableSetOf()

    /**
     * A suspending method to do your work.  This function runs on the coroutine context specified
     * by [coroutineContext].
     * <p>
     * A CoroutineWorker is given a maximum of ten minutes to finish its execution and return a
     * [ListenableWorker.Result].  After this time has expired, the worker will be signalled to
     * stop.
     *
     * @return The [ListenableWorker.Result] of the result of the background work; note that
     * dependent work will not execute if you return [ListenableWorker.Result.failure]
     */
    override suspend fun doWork(): Result = withContext(coroutineContext) {
        try {
            createNotificationChannel()
            downloadFileFromDBEntries()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry()
        }
        Result.success()
    }

    private suspend fun downloadFileFromDBEntries() {
        val list = mDatabaseDao.fetchRemainingDownloadItems()
        val jobList = mutableListOf<Deferred<com.app.mydownloadmanager.Result>>()
        for (item in list) {
            jobList.add(downloadFileAsync(item))
        }
        jobList.awaitAll()
        for (item in jobList) {
            if (item.isCompleted) {
                when (val result = item.getCompleted()) {
                    is com.app.mydownloadmanager.Result.Error -> {
                        if (result.exception is MalformedURLException) {
                            result.downloadItem.downloadStatus =
                                DownloadItem.DOWNLOAD_STATUS_COMPLETED
                            mDatabaseDao.updateDownloadStatus(result.downloadItem)
                        }
                    }
                    is com.app.mydownloadmanager.Result.Success -> {
                        result.downloadItem.downloadStatus = DownloadItem.DOWNLOAD_STATUS_COMPLETED
                        mDatabaseDao.updateDownloadStatus(result.downloadItem)
                    }
                }
            }
        }
        if (mDatabaseDao.fetchAvailableUrlToDownloadCount() > 0) {
            downloadFileFromDBEntries()
        }
    }

    private var mNotificationBuilder: NotificationCompat.Builder? = null


    private suspend fun createNotification() = withContext(Dispatchers.Main) {
        if (mNotificationBuilder == null) {
            mNotificationBuilder = NotificationCompat.Builder(
                applicationContext,
                CHANNEL_ID
            ).apply {
                setContentTitle("Picture Download")
                setContentText("Download in progress")
                setSmallIcon(R.mipmap.ic_launcher)
                setOngoing(true)
                setAutoCancel(false)
                addAction(
                    NotificationCompat.Action(
                        -1,
                        "Cancel",
                        PendingIntent.getBroadcast(
                            this@DownloaderService.applicationContext,
                            0,
                            Intent(
                                this@DownloaderService.applicationContext,
                                NotificationActionBroadcastReceiver::class.java
                            ).apply {
                                action = "Cancel"
                            },
                            0
                        )
                    )
                )
                priority = NotificationCompat.PRIORITY_LOW
            }
        }
        val PROGRESS_MAX = 100
        mNotificationManagerCompat.apply {
            // Issue the initial notification with zero progress
            mNotificationBuilder?.setProgress(100, 0, false)
            notify(NOTIFICATION_ID, mNotificationBuilder?.build() ?: return@apply)
        }

    }

    private suspend fun showDownloadNotification(progress: Int) = withContext(Dispatchers.Main) {
        val PROGRESS_MAX = 100

        // Issue the initial notification with zero progress

        mNotificationManagerCompat.notify(
            NOTIFICATION_ID,
            (mNotificationBuilder ?: return@withContext).apply {
                setProgress(PROGRESS_MAX, progress, false)
                setContentText("$progress %")
                setNotificationSilent()
            }.build()
        )
    }

    val lock = Mutex(false)
    private suspend fun completeUploadNotification() = withContext(Dispatchers.Main) {
        synchronized(lock.lock()) {
            mNotificationManagerCompat.notify(
                NOTIFICATION_ID,
                (mNotificationBuilder ?: return@withContext).apply {
                    setContentText("File Downloaded")
                    setOngoing(false)
                    setAutoCancel(true)
                    try {
                        //Use reflection clean up old actions
                        val f: Field = this.javaClass.getDeclaredField("mActions")
                        f.isAccessible = true
                        f.set(
                            this, arrayListOf(
                                NotificationCompat.Action(
                                    -1,
                                    "Done",
                                    PendingIntent.getBroadcast(
                                        applicationContext,
                                        0,
                                        Intent(
                                            applicationContext,
                                            NotificationActionBroadcastReceiver::class.java
                                        ).apply {
                                            action = "Done"
                                        },
                                        0
                                    )
                                )
                            )
                        )
                    } catch (e: Exception) {
                        // no field
                        e.printStackTrace()
                    }
                    setProgress(0, 0, false)
                }.build()
            )
        }

    }

    private suspend fun failedFileDownloading() = withContext(Dispatchers.Main) {
        mNotificationManagerCompat.notify(
            NOTIFICATION_ID,
            (mNotificationBuilder ?: return@withContext).apply {
                setContentText("Unable to download File")
                setOngoing(false)
                setAutoCancel(true)
                setProgress(0, 0, false)
            }.build()
        )
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val descriptionText = "Download progress bar"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            mNotificationManagerCompat.createNotificationChannel(channel)
        }
    }

    val isDebug = true
    private fun downloadFileAsync(@NonNull downloadItem: DownloadItem): Deferred<com.app.mydownloadmanager.Result> =
        async(coroutineContext) {
            var downloadedFileSize = 0
            try {
                val httpConnection =
                    URL(downloadItem.downloadUrl).openConnection() as? HttpURLConnection
                        ?: throw UnsupportedOperationException()
                httpConnection.requestMethod = "POST"
                if (!isDebug)
                    httpConnection.connect()
                if (isDebug || httpConnection.responseCode == HttpURLConnection.HTTP_OK) {
                    val fileSize = if (!isDebug) httpConnection.contentLength else 100
                    val bufferSize = if (fileSize / (1024 * 1024 * 1024) > 0) {
                        val mb = fileSize / (1024 * 1024)
                        if (mb > 500)
                            1024 * 1024 * 50
                        else
                            1024 * 1024 * 10
                    } else if (fileSize / (1024 * 1024) > 0)
                        1024 * 1024
                    else
                        1024
                    mDownloadingFile[downloadItem.downloadUrl] = fileSize to 0
                    mTotalDownloadSize.addAndGet(fileSize.toLong())
//                    httpConnection.inputStream.runCatching {
                        val fileDirectory = File(applicationContext.filesDir, "files")
                        if (!fileDirectory.exists())
                            fileDirectory.mkdirs()
                        val filename =
                            File(fileDirectory, downloadItem.downloadUrl.split('/').last())
                        val fileOutputStream = FileOutputStream(filename)
                        var bytesRead = -1
                        val buffer = ByteArray(1024)
                        bytesRead = if (isDebug) 0 else 0 //read(buffer)
                        createNotification()
                        while (isDebug || bytesRead != -1) {
//                            fileOutputStream.write(buffer, 0, bytesRead)
                            downloadedFileSize += bytesRead
                            mDownloadingFile[downloadItem.downloadUrl] =
                                fileSize to downloadedFileSize
                            var totalDownloaded = 0
                            for (item in mDownloadingFile.values) {
                                totalDownloaded += item.second
                            }
                            val percent = totalDownloaded
                                .coerceAtLeast(0) * 100 / mTotalDownloadSize.get().coerceAtLeast(1)
                            showDownloadNotification(percent.toInt())
                            if (isDebug && percent == 100.toLong())
                                break
                            delay(1000 * 30)
                            bytesRead = if (isDebug) 20 else 0 // read(buffer)
                        }
                        completeUploadNotification()
//                        fileOutputStream.flush()
//                        fileOutputStream.close()
//                        close()
//                    }
                    httpConnection.disconnect()
                } else {
                    mDownloadingFile[downloadItem.downloadUrl]?.run {
                        mDownloadingFile[downloadItem.downloadUrl] = first to 0
                    }
                    handleDownloadFail(downloadItem.downloadUrl)
                    return@async com.app.mydownloadmanager.Result.Error(
                        downloadItem,
                        Exception(httpConnection.responseMessage)
                    )
                }
//                createNotification()
//                var progress = 0
//                mTotalDownloadSize.addAndGet(100)
//                while (true) {
//
//                    mDownloadedFileSize.addAndGet(progress.toLong())
//                    showDownloadNotification(progress)
//                    delay(1000 * 30)
//                    if (progress == 100)
//                        break
//                }
//                completeUploadNotification()
            } catch (malformedUrlException: MalformedURLException) {
                mDownloadingFile[downloadItem.downloadUrl]?.run {
                    mDownloadingFile[downloadItem.downloadUrl] = first to 0
                }
                handleDownloadFail(downloadItem.downloadUrl)
                return@async com.app.mydownloadmanager.Result.Error(
                    downloadItem,
                    malformedUrlException
                )
            } catch (e: Exception) {
                mDownloadingFile[downloadItem.downloadUrl]?.run {
                    mDownloadingFile[downloadItem.downloadUrl] = first to 0
                }
                handleDownloadFail(downloadItem.downloadUrl)
                return@async com.app.mydownloadmanager.Result.Error(downloadItem, e)
            }
            return@async com.app.mydownloadmanager.Result.Success(downloadItem)
        }

    private suspend fun handleDownloadFail(url: String) {
        mTotalDownloadSize.addAndGet(-(mDownloadingFile[url]?.first ?: 0).toLong())
        mDownloadingFile.remove(url)
        mFailedDownloads.add(url)
        failedFileDownloading()
    }

    inner class NotificationActionBroadcastReceiver : BroadcastReceiver() {
        /**
         * This method is called when the BroadcastReceiver is receiving an Intent
         * broadcast.  During this time you can use the other methods on
         * BroadcastReceiver to view/modify the current result values.  This method
         * is always called within the main thread of its process, unless you
         * explicitly asked for it to be scheduled on a different thread using
         * [android.content.Context.registerReceiver]. When it runs on the main
         * thread you should
         * never perform long-running operations in it (there is a timeout of
         * 10 seconds that the system allows before considering the receiver to
         * be blocked and a candidate to be killed). You cannot launch a popup dialog
         * in your implementation of onReceive().
         *
         *
         * **If this BroadcastReceiver was launched through a &lt;receiver&gt; tag,
         * then the object is no longer alive after returning from this
         * function.** This means you should not perform any operations that
         * return a result to you asynchronously. If you need to perform any follow up
         * background work, schedule a [android.app.job.JobService] with
         * [android.app.job.JobScheduler].
         *
         * If you wish to interact with a service that is already running and previously
         * bound using [bindService()][android.content.Context.bindService],
         * you can use [.peekService].
         *
         *
         * The Intent filters used in [android.content.Context.registerReceiver]
         * and in application manifests are *not* guaranteed to be exclusive. They
         * are hints to the operating system about how to find suitable recipients. It is
         * possible for senders to force delivery to specific recipients, bypassing filter
         * resolution.  For this reason, [onReceive()][.onReceive]
         * implementations should respond only to known actions, ignoring any unexpected
         * Intents that they may receive.
         *
         * @param context The Context in which the receiver is running.
         * @param intent The Intent being received.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            mNotificationManagerCompat?.cancelAll()
        }

    }

    companion object {
        private const val NOTIFICATION_ID = 0x007
        private const val CHANNEL_ID = "com.app.mydownloadmanager"
        private const val CHANNEL_NAME = "ProgressBar"
    }
}

sealed class Result {
    data class Error(val downloadItem: DownloadItem, val exception: Exception) : Result()
    data class Success(val downloadItem: DownloadItem) : Result()
}