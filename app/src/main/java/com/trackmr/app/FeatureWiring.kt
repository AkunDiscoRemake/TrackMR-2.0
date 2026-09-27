package com.trackmr.app

import android.net.Uri
import android.view.KeyEvent
import com.trackmr.cinema.CinemaApp
import com.trackmr.cinema.CinemaModule
import com.trackmr.environments.EnvironmentModule
import com.trackmr.environments.EnvironmentsApp
import com.trackmr.xr.XrContext

/** Concrete feature hookup: every platform feature contributes modules + launcher entries. */
internal object FeatureWiring {
    private var env: EnvironmentModule? = null
    private var cinema: CinemaModule? = null

    fun installEarly(ctx: XrContext) {
        env = EnvironmentModule().also { ctx.addModule(it) }
    }

    fun install(ctx: XrContext) {
        env?.let { EnvironmentsApp.register(ctx, it) }
        cinema = CinemaModule().also { ctx.addModule(it); CinemaApp.register(ctx, it) }
    }

    fun openUri(ctx: XrContext, uri: Uri, mime: String?) {
        val path = uri.lastPathSegment?.lowercase() ?: ""
        val isVideo = mime?.startsWith("video/") == true || VIDEO_EXT.any { path.endsWith(it) }
        if (isVideo) { cinema?.let { CinemaApp.openUri(it, uri) }; return }
        ctx.notify("Abrir: $uri")
    }

    private val VIDEO_EXT = listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mov")
    fun dispatchKey(ctx: XrContext, event: KeyEvent): Boolean = false
}
