package com.trackmr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * The only Android activity. It hosts a single fullscreen GL surface and starts straight in
 * mixed reality (camera passthrough + spatial UI) — there is no 2D Android UI.
 */
class XrActivity : Activity() {
    private var session: XrSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && pm?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }
        val s = XrSession(this) { recreate() }
        session = s
        setContentView(s.view)
        hideSystemUi()
        handleIntent(intent)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** VIEW intents (links, videos) are opened in the XR browser / cinema. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val s = session ?: return
        FeatureRegistry.openUri(s.ctx, data, intent.type)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            session?.onCameraPermissionResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        session?.resume()
    }

    override fun onPause() {
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.destroy()
        session = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // VR Box remotes / Bluetooth gamepads: A/Enter = select, B/Back/Menu = home menu.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        if (FeatureRegistry.dispatchKey(s.ctx, event)) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (event.repeatCount == 0) s.onSelectButton(true); true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> {
                if (event.repeatCount == 0) s.onMenuButton(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyUp(keyCode, event)
        if (FeatureRegistry.dispatchKey(s.ctx, event)) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_R2 -> { s.onSelectButton(false); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    companion object { private const val REQ_CAMERA = 10 }
}
