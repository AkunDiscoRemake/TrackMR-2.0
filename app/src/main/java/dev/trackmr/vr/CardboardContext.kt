package dev.trackmr.vr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build

/** The SDK keeps global context references. Never give it an Activity/GLSurfaceView tree. */
class CardboardContext private constructor(base: Context) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) {
        super.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    companion object {
        fun from(activity: Activity): CardboardContext {
            // Android 11+ ScreenParamsUtils requires a display-associated Context.
            val base=if(Build.VERSION.SDK_INT>=30) {
                activity.display?.let { activity.applicationContext.createDisplayContext(it) }
                    ?: activity.applicationContext
            } else activity.applicationContext
            return CardboardContext(base)
        }
    }
}
