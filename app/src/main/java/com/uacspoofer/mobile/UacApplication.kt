package com.uacspoofer.mobile

import android.app.Application
import android.content.Context
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.logging.CrashReportStore
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.widget.UacHomeWidgetProvider

class UacApplication : Application() {
    override fun attachBaseContext(base: Context) {
        UpgradeReset.run(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        AppThemeStore.get(this)
        EngineModeStore.get(this)
        UacHomeWidgetProvider.bind(this)
    }
}
