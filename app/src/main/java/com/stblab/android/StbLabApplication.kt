package com.stblab.android

import android.app.Application
import com.xstream.remote.RemoteV2Client

class StbLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteV2Client.get(this)
    }
}
