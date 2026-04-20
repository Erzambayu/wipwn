package com.wipwn.app.util

import com.topjohnwu.superuser.Shell

/**
 * Abstraction layer over shell command execution so the rest of the app
 * does not depend directly on libsu. This makes the code testable via fakes.
 */
interface ShellExecutor {
    fun exec(vararg commands: String): ShellResult
    fun execAsync(vararg commands: String, onLine: (String) -> Unit = {}): ShellResult
}

data class ShellResult(
    val isSuccess: Boolean,
    val code: Int,
    val out: List<String>,
    val err: List<String>
) {
    val stdout: String get() = out.joinToString("\n")
    val stderr: String get() = err.joinToString("\n")
    val combined: String get() = (out + err).joinToString("\n")
}

/**
 * Default implementation backed by libsu. Runs every command through the
 * cached root shell.
 */
class LibSuShellExecutor : ShellExecutor {
    override fun exec(vararg commands: String): ShellResult {
        val res = Shell.cmd(*commands).exec()
        return ShellResult(
            isSuccess = res.isSuccess,
            code = res.code,
            out = res.out,
            err = res.err
        )
    }

    override fun execAsync(vararg commands: String, onLine: (String) -> Unit): ShellResult {
        val callbackList = object : ArrayList<String>() {
            override fun add(element: String): Boolean {
                onLine(element)
                return super.add(element)
            }
        }
        val res = Shell.cmd(*commands).to(callbackList).exec()
        return ShellResult(
            isSuccess = res.isSuccess,
            code = res.code,
            out = res.out,
            err = res.err
        )
    }
}
