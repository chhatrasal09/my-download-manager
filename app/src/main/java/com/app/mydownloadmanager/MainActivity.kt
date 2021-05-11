package com.app.mydownloadmanager

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val mDownloadManager: DownloadManagr by lazy { DownloadManagrImpl() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View?>(R.id.download_button)?.setOnClickListener {
            val input = findViewById<AppCompatEditText?>(R.id.url_input)?.text?.toString()
            if (input.isNullOrEmpty()) {
                Toast.makeText(
                    this,
                    "Url cannot be empty. Please enter the url!",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                mDownloadManager.startDownload(this@MainActivity, input)
            }
        }
    }
}