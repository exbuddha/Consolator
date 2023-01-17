package backgammon.module

import android.content.*

abstract class BaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {}
}