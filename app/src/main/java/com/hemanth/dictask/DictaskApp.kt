package com.hemanth.dictask

import android.app.Application
import com.hemanth.dictask.data.Sync

class DictaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Live sync with the laptop for as long as the process lives (UI, widget taps, reminders).
        if (Sync.signedIn) Sync.start(this)
    }
}
