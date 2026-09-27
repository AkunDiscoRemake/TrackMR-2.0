package com.trackmr.app

import android.net.Uri
import android.view.KeyEvent
import com.trackmr.xr.XrContext

/** Concrete feature hookup (grows as feature modules are added). */
internal object FeatureWiring {
    fun installEarly(ctx: XrContext) {}
    fun install(ctx: XrContext) {}
    fun openUri(ctx: XrContext, uri: Uri, mime: String?) { ctx.notify("Abrir: $uri") }
    fun dispatchKey(ctx: XrContext, event: KeyEvent): Boolean = false
}
