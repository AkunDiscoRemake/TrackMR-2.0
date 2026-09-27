package com.trackmr.app

import android.net.Uri
import android.view.KeyEvent
import com.trackmr.xr.XrContext

/**
 * Wires optional platform features (environments, browser, cinema, Android windows,
 * emulation, ALVR, OpenXR, games) into the session. Each feature registers its XrModule(s)
 * and its launcher entries in [com.trackmr.ui.AppRegistry].
 */
object FeatureRegistry {
    /** Features that must attach before hands/UI (e.g. OpenXR hand tracking, VR environments). */
    fun installEarly(ctx: XrContext) {
        FeatureWiring.installEarly(ctx)
    }

    fun install(ctx: XrContext) {
        FeatureWiring.install(ctx)
    }

    fun openUri(ctx: XrContext, uri: Uri, mime: String?) {
        ctx.runOnGl { FeatureWiring.openUri(ctx, uri, mime) }
    }

    /** Gives features (emulators, ALVR) first chance at gamepad keys. */
    fun dispatchKey(ctx: XrContext, event: KeyEvent): Boolean = FeatureWiring.dispatchKey(ctx, event)
}
