package com.wipwn.app.util

import com.topjohnwu.superuser.Shell

/**
 * Thin helpers around root detection / management. Prefer injecting a
 * [ShellExecutor] in real logic; this utility exists for the very first
 * root probe which has to happen before anything else.
 */
object RootUtil {
    /**
     * Returns true if the app has an active root shell. Blocking — call from
     * IO dispatcher.
     */
    fun isRooted(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
}
