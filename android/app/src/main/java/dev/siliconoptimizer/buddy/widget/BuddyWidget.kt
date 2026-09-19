package dev.siliconoptimizer.buddy.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.updateAll
import dev.siliconoptimizer.buddy.MainActivity
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.BuddySnapshot
import dev.siliconoptimizer.buddy.reach.BuddyWidgetEntry
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.reach.WidgetTimeline
import dev.siliconoptimizer.buddy.transport.ControlClient

/**
 * The home screen widget: what the Mac has loaded, the last answer, and one tap.
 *
 * Glance rather than RemoteViews — a second UI toolkit in this app for one screen's
 * worth of content would not be worth it — and all of the thinking is in
 * `WidgetTimeline`, which knows nothing about Glance and is therefore tested against a
 * real socket.
 */
class BuddyWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshots = SnapshotStore(context)
        val config = TokenStore(context).load()
        val entry = WidgetTimeline.entry(
            transport = config?.let { ControlClient(it) },
            stored = snapshots.read(),
            quickPrompt = snapshots.quickPrompt,
            quickAnswer = snapshots.quickAnswer,
        )
        provideContent { GlanceTheme { WidgetBody(context, entry) } }
    }

    @Composable
    private fun WidgetBody(context: Context, entry: BuddyWidgetEntry) {
        // The same three facts at both sizes; the wide one has room for its buttons to
        // carry words rather than only an icon's worth of meaning.
        val wide = LocalSize.current.width >= 200.dp
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                // The whole surface opens the composer, which is the one-tap "Ask".
                // The whole surface opens the composer, which is the one-tap "Ask".
                // A `siliconbuddy://ask` intent rather than a bare activity start, so
                // the widget, the tile and the launcher shortcut all arrive at
                // MainActivity by exactly the same door.
                .clickable(actionStartActivity(composeIntent(context))),
        ) {
            Text(
                entry.headline,
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.padding(2.dp))
            Text(
                entry.body(if (wide) 180 else 90)
                    ?: entry.problem
                    ?: "Tap to ask your Mac.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = if (wide) 4 else 3,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (entry.isPaired) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        if (wide) BuddySnapshot.trim(entry.quickPrompt, 28) else "Ask",
                        style = TextStyle(color = GlanceTheme.colors.primary),
                        maxLines = 1,
                        modifier = GlanceModifier
                            .clickable(
                                actionRunCallback<AskQuickPromptAction>(
                                    actionParametersOf(PROMPT to entry.quickPrompt),
                                ),
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    if (entry.quickAnswer != null) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            "Clear",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier
                                .clickable(actionRunCallback<ClearQuickAnswerAction>())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    companion object {
        val PROMPT = ActionParameters.Key<String>("prompt")

        fun composeIntent(context: Context): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("siliconbuddy://ask"))
                .setClass(context, MainActivity::class.java)
    }
}

class BuddyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BuddyWidget()
}

/**
 * The widget's own button.
 *
 * Runs in the app's process without a window: the point is an answer without a launch.
 * It asks once with no history and writes what came back where the next redraw reads
 * it. Both outcomes are stored, because a widget that silently does nothing when the
 * Mac is asleep is indistinguishable from one that is broken.
 */
class AskQuickPromptAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val snapshots = SnapshotStore(context)
        val prompt = parameters[BuddyWidget.PROMPT] ?: snapshots.quickPrompt
        val config = TokenStore(context).load()
        snapshots.quickAnswer = WidgetTimeline.ask(
            prompt, config?.let { ControlClient(it) }, snapshots,
        ).text
        BuddyWidget().updateAll(context)
    }
}

/** Puts the widget back to showing what is loaded. */
class ClearQuickAnswerAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        SnapshotStore(context).quickAnswer = null
        BuddyWidget().updateAll(context)
    }
}
