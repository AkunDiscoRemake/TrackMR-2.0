package dev.trackmr.platform

import android.os.Process
import java.util.concurrent.TimeUnit

/** Runs ONLY as a Shizuku user service (shell/root). No network or arbitrary shell entry point. */
class ShellBridgeService : IShellBridge.Stub() {
    override fun uid() = Process.myUid()
    override fun launchOnDisplay(component: String, displayId: Int): String {
        require(displayId > 0) { "Somente displays virtuais, nunca o display principal" }
        require(component.length < 300 && component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")))
        return command("am","start","--display",displayId.toString(),"-n",component)
    }
    override fun resizeDisplay(displayId: Int, width: Int, height: Int): String {
        require(displayId > 0 && width in 320..3840 && height in 240..2160)
        return command("wm","size","${width}x$height","-d",displayId.toString())
    }
    private fun command(vararg args: String): String {
        val process=ProcessBuilder(*args).redirectErrorStream(true).start()
        // Commands produce little output. Bound execution time and diagnostic payload.
        if (!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); return "Timeout do serviço shell" }
        return process.inputStream.bufferedReader().use { it.readText().take(4096) }
    }
    override fun destroy() { Process.killProcess(Process.myPid()) }
}
