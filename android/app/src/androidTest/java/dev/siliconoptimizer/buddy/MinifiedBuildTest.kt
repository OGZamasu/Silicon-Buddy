package dev.siliconoptimizer.buddy

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.glance.appwidget.action.ActionCallback
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What R8 cannot see, checked on a device, against the minified build.
 *
 * `testBuildType` is release, so this runs against the APK R8 actually produced. The
 * failure these exist for is silent: Glance never *calls* an `ActionCallback`, it writes
 * the class name into a RemoteViews action and instantiates it by name when the button
 * is tapped, in a different process, possibly days later. Strip or rename it and the
 * build still succeeds, the widget still draws, and its one button just does nothing.
 * Same shape for the wire types, which exist only at the end of a
 * `Class.forName`-shaped path through kotlinx.serialization.
 *
 * `seeds.txt` is checked in CI, which is cheaper and catches the same thing statically.
 * This is the version that runs the real classloader over the real APK.
 *
 * Everything here goes through `Class.forName` with an unobfuscated name, which is the
 * whole test — it only resolves because a keep rule preserved that name. Tests that
 * referenced app classes directly were tried here and removed: they fail against a
 * minified build because R8 renames those classes, which is R8 working correctly, not a
 * bug. What they were checking (the tailnet gate, the wire types) is covered by
 * `TailnetHostTest` and `ContractTest` on the JVM, where it belongs.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedBuildTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Exactly how Glance resolves a callback: by name, through the app's classloader.
     *
     * Not `AskQuickPromptAction::class.java`, which is a compile-time reference R8 would
     * have seen and kept — that would test nothing. The string is what actually travels
     * inside the action.
     */
    private fun resolve(className: String): Class<*> =
        Class.forName(className, false, context.classLoader)

    @Test
    fun theWidgetsButtonCallbackSurvivesMinification() {
        val callback = resolve("dev.siliconoptimizer.buddy.widget.AskQuickPromptAction")
        assertTrue(
            "AskQuickPromptAction must implement ActionCallback for Glance to run it",
            ActionCallback::class.java.isAssignableFrom(callback),
        )
        // Glance instantiates it with the no-argument constructor.
        assertNotNull("It needs a no-arg constructor", callback.getDeclaredConstructor())
    }

    @Test
    fun theClearAnswerCallbackSurvivesMinification() {
        val callback = resolve("dev.siliconoptimizer.buddy.widget.ClearQuickAnswerAction")
        assertTrue(ActionCallback::class.java.isAssignableFrom(callback))
        assertNotNull(callback.getDeclaredConstructor())
    }

    /** The receiver the system names in the manifest, and the widget it hands back. */
    @Test
    fun theWidgetProviderIsRegistered() {
        val provider = ComponentName(
            context,
            "dev.siliconoptimizer.buddy.widget.BuddyWidgetReceiver",
        )
        val registered = AppWidgetManager.getInstance(context)
            .installedProviders
            .any { it.provider == provider }
        assertTrue("The launcher cannot offer a widget whose provider is gone", registered)
    }
}
