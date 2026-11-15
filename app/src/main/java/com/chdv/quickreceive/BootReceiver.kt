package com.chdv.quickreceive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        if (p.enabled && p.startOnBoot) runCatching { ReceiverService.start(context) }
    }
}
