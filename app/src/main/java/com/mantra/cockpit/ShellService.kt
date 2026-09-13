package com.mantra.cockpit

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * THE SHELL USER'S HANDS. This class runs in a process Shizuku starts with the shell's rights
 * (or root under Sui), not in this app. One method: run a command, bounded, return its text.
 * Constructors are required by Shizuku; @Keep stops R8 from removing the one with a Context.
 */
class ShellService() : IShell.Stub() {

    @Keep
    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun run(command: String, timeoutMs: Int): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).directory(File("/")).start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (out.length < 2_000_000) out.append(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                // the stream closed under us: what was read is what there is
            }
        }
        reader.start()
        if (!p.waitFor(timeoutMs.toLong().coerceIn(500, 60_000), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(1_000)
            return out.toString() + "\n[cockpit: timed out after $timeoutMs ms]"
        }
        reader.join(2_000)
        return out.toString()
    }
}
