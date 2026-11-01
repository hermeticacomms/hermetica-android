package com.hermeticacomms.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Foreground/background from activity start/stop counts, and the resumed activity the in-app presenter draws on. */
internal class Lifecycle(private val onForeground: () -> Unit, private val onBackground: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    @Volatile private var current: WeakReference<Activity>? = null

    val inForeground: Boolean get() = started > 0
    val resumed: Activity? get() = current?.get()?.takeIf { !it.isFinishing }

    override fun onActivityStarted(activity: Activity) { if (started++ == 0) onForeground() }
    override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1); if (started == 0) onBackground() }
    override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) { if (current?.get() === activity) current = null }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
