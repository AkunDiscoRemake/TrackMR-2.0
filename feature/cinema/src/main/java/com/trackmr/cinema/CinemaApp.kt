package com.trackmr.cinema

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.trackmr.ui.AppCategory
import com.trackmr.ui.AppRegistry
import com.trackmr.ui.UiTheme
import com.trackmr.ui.XrApp
import com.trackmr.xr.XrContext

object CinemaApp {
    fun register(ctx: XrContext, cinema: CinemaModule) {
        AppRegistry.register(XrApp("cinema", "Cinema", "Tela gigante curva", "🎬", UiTheme.neonRed, AppCategory.MEDIA) { c ->
            requestMediaPermission(c)
            cinema.open()
        })
    }

    /** Opens a video URI (VIEW intent, browser download, file manager). GL thread. */
    fun openUri(cinema: CinemaModule, uri: Uri) {
        cinema.open(VideoEntry(uri.lastPathSegment ?: "Vídeo", uri))
    }

    private fun requestMediaPermission(ctx: XrContext) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ctx.activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            ctx.runOnUi { ctx.activity.requestPermissions(arrayOf(perm), 21) }
        }
    }
}
