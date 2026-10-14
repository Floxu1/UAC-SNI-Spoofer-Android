package com.uacspoofer.mobile

import android.app.Application
import android.content.Context
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.logging.CrashReportStore

class UacApplication : Application() {
    override fun attachBaseContext(base: Context) {
        UpgradeReset.run(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        EngineModeStore.get(this)
    }
}
