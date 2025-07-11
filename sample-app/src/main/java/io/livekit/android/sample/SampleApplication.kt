package io.livekit.android.sample

import android.app.Application
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import timber.log.Timber

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveKit.loggingLevel = LoggingLevel.VERBOSE
        LiveKit.enableWebRTCLogging = true
        LiveKit.setLogToDebug(true)
//        LKLog.registerLogCallback( object : LKLog.LogCallback {
//            override fun onLog(level: LoggingLevel, message: String?, throwable: Throwable?) {
//                println("***track: $level, $message, ")
//            }
//        })
    }
}
