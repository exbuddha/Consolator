package net.consolator

import android.content.*

internal class BaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            else -> {}
    } }
}