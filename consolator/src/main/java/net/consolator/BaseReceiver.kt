package net.consolator

import android.content.*

class BaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            else -> {}
    } }
}