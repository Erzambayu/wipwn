package com.wipwn.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import com.wipwn.app.data.AttackHistoryStore
import com.wipwn.app.repository.WpsRepository
import com.wipwn.app.util.LibSuShellExecutor

class WipwnApp : Application() {

    lateinit var repository: WpsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = WpsRepository(
            appContext = applicationContext,
            shell = LibSuShellExecutor(),
            historyStore = AttackHistoryStore(applicationContext)
        )
    }

    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(30)
            )
        }
    }
}
