package com.mantra.cockpit

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * SHIZUKU, WHEN IT IS THERE.
 *
 * Android 14 and newer let no app kill another (killBackgroundProcesses touches only the caller's
 * own package, whatever it targets). The shell user still can, and Shizuku hands an app the shell
 * user's rights once the person has installed Shizuku and started it (over Wireless debugging, or
 * ADB from a computer). So: with Shizuku, this app force-stops apps and reads dumpsys meminfo and
 * the real /proc/stat; without it, it opens App Info and says so. Nothing here pretends.
 */
class Bridge private constructor(private val context: Context) {

    companion object {
        const val REQUEST = 4711
        @Volatile var instance: Bridge? = null
        fun get(context: Context): Bridge = instance ?: Bridge(context.applicationContext).also { instance = it }
    }

    @Volatile var shell: IShell? = null
    @Volatile var state: String = "not asked"

    val installed: Boolean
        get() = try { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    val alive: Boolean
        get() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    val granted: Boolean
        get() = try { alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }

    val ready: Boolean get() = shell != null

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false).processNameSuffix("shell").debuggable(false).version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            shell = IShell.Stub.asInterface(binder)
            state = "the shell is ready"
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shell = null
            state = "the shell went away"
        }
    }

    /** Where things stand, in one sentence for the screen. */
    fun describe(): String = when {
        !installed -> "Shizuku is not installed (github.com/RikkaApps/Shizuku, the APK): without it apps can only be stopped from App Info"
        !alive -> "Shizuku is installed but not running: start it in the Shizuku app"
        !granted -> "Shizuku is running: allow Cockpit in it"
        ready -> "Shizuku: the shell is ready ($state)"
        else -> "Shizuku is allowed: binding the shell ($state)"
    }

    fun request() {
        if (!alive) return
        if (granted) { bind(); return }
        try { Shizuku.requestPermission(REQUEST) } catch (e: Throwable) { state = "could not ask: $e" }
    }

    fun onPermissionResult(code: Int, result: Int) {
        if (code == REQUEST && result == PackageManager.PERMISSION_GRANTED) bind()
    }

    fun bind() {
        if (!granted || ready) return
        try {
            Shizuku.bindUserService(args, connection)
            state = "binding"
        } catch (e: Throwable) {
            state = "bind failed: $e"
        }
    }

    fun unbind() {
        try { Shizuku.unbindUserService(args, connection, true) } catch (e: Throwable) { /* already gone */ }
        shell = null
    }

    /** One command as the shell user; null when the shell is not there. Bounded by the service. */
    fun run(command: String, timeoutMs: Int = 10_000): String? = try { shell?.run(command, timeoutMs) } catch (e: Throwable) { state = "call failed: $e"; shell = null; null }

    /** am force-stop: the same as App Info's Force stop, and the only kill that means anything here. */
    fun forceStop(pkg: String): Boolean {
        if (!ShellText.PACKAGE.matches(pkg)) return false
        return run("am force-stop $pkg", 8_000) != null
    }

    fun killAll(): Boolean = run("am kill-all", 15_000) != null
}
