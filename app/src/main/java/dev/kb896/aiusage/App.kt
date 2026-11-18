package dev.kb896.aiusage

import android.app.Application

/**
 * The app's process as it starts, whatever started it (BootReceiver after a restart, Shizuku handing over its binder,
 * an activity, the notification listener): the one place that runs at every start. It only listens for Shizuku
 * (service/ShizukuArrival), and only in the main process; the panel's ":rear" process has nothing to do with it.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        dev.kb896.aiusage.service.ShizukuArrival.install(this)
    }
}
