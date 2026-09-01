package com.webmediacapture.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.webmediacapture.WebMediaCaptureApp
import kotlinx.coroutines.launch

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadWorker.ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as WebMediaCaptureApp
        val queue = DownloadManager(DownloadRepository(app, app.database.downloads()))
        app.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> queue.pause(id)
                    ACTION_RESUME -> queue.resume(id)
                    ACTION_CANCEL -> queue.cancel(id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.webmediacapture.action.PAUSE"
        const val ACTION_RESUME = "com.webmediacapture.action.RESUME"
        const val ACTION_CANCEL = "com.webmediacapture.action.CANCEL"
    }
}
