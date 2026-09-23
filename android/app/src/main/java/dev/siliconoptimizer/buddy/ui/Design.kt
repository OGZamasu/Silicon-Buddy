package dev.siliconoptimizer.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The small amount of shared look the app needs: a card, a labelled stat, a bar, a
 * pill. Everything else is stock Material 3, which is the point — it should feel like
 * an Android app, not like an iOS app in a costume.
 */
object Format {
    fun bytes(value: Long?): String {
        if (value == null) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1000 && unit < units.lastIndex) {
            size /= 1000
            unit++
        }
        return if (unit == 0) "$value B"
        else String.format(Locale.US, "%.2f %s", size, units[unit])
    }

    fun gigabytes(value: Double): String = String.format(Locale.US, "%.1f GB", value)

    fun megabytes(value: Long): String =
        String.format(Locale.US, "%.1f MB", value / 1024.0 / 1024.0)

    fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

    fun rate(tokensPerSecond: Double?): String =
        if (tokensPerSecond == null || tokensPerSecond <= 0) "—"
        else String.format(Locale.US, "%.1f tok/s", tokensPerSecond)

    fun secondsAgo(value: Double?): String = when {
        value == null -> "—"
        value < 60 -> String.format(Locale.US, "%.0f s ago", value)
        else -> String.format(Locale.US, "%.0f min ago", value / 60)
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                        .padding(8.dp).size(18.dp),
                )
                // A machine's name is a card's title on the Machines tab, beside its address.
                SplitLine(
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    start = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    end = {
                        if (footnote != null) {
                            Text(
                                footnote,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                )
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
    ) {
        // No line limit: in a `StatRow` the column is at least as wide as the longest word,
        // so the value can only wrap between words ("137.44 / GB"), never inside one.
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [Stat]s side by side, as many to a row as fit whole: three on a phone at the usual text
 * size, fewer as the text grows. No column is narrower than the longest word any of them
 * holds, so a large font moves a stat to the next row rather than breaking "Normal" or
 * "402.65" in two.
 */
@Composable
fun StatRow(modifier: Modifier = Modifier, spacing: Dp = 12.dp, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val gap = spacing.roundToPx()
        // A Text's minimum intrinsic width is its longest unbreakable run.
        val widest = measurables.maxOf { it.minIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else measurables.size * (widest + gap) - gap
        val columns = ((width + gap) / (widest + gap)).coerceIn(1, measurables.size)
        val cell = (width - gap * (columns - 1)) / columns
        val rows = measurables
            .map { it.measure(Constraints(minWidth = cell, maxWidth = cell)) }
            .chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + gap * (rows.size - 1)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            rows.forEachIndexed { index, row ->
                row.forEachIndexed { column, stat -> stat.placeRelative(column * (cell + gap), y) }
                y += heights[index] + gap
            }
        }
    }
}

/**
 * Two things that belong on one line — a name at the start, what it reads at the end — side
 * by side when both fit whole, and otherwise the end under the start. A Row with a weighted
 * start gives the end all it asks for and the start whatever is left, so at 200% font
 * "38.65 GB of 137.44 GB" left "Memory" a sliver and it broke as "Memor / y"; a swarm peer's
 * URL did the same to "render-node". Each slot is one element.
 */
@Composable
fun SplitLine(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Layout(listOf(start, end), modifier.fillMaxWidth()) { (starts, ends), constraints ->
        val first = starts.firstOrNull()
        val second = ends.firstOrNull()
        if (first == null || second == null) {
            // One of them had nothing to show — a card with no footnote.
            val only = (first ?: second)?.measure(constraints.copy(minWidth = 0))
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else only?.width ?: 0
            return@Layout layout(width, only?.height ?: 0) { only?.placeRelative(0, 0) }
        }
        val gap = spacing.roundToPx()
        val startWhole = first.maxIntrinsicWidth(Constraints.Infinity)
        val endWhole = second.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else startWhole + gap + endWhole
        val startWidth = sideBySide(width, gap, startWhole, endWhole)
        if (startWidth != null) {
            val a = first.measure(Constraints(maxWidth = startWidth))
            val b = second.measure(Constraints(maxWidth = endWhole))
            val height = maxOf(a.height, b.height)
            layout(width, constraints.constrainHeight(height)) {
                a.placeRelative(0, (height - a.height) / 2)
                b.placeRelative(width - b.width, (height - b.height) / 2)
            }
        } else {
            // Stacked, each has the whole width: a label wraps between its words, and a name
            // or an address that is longer than the line ellipsizes rather than breaking.
            val a = first.measure(Constraints(maxWidth = width))
            val b = second.measure(Constraints(maxWidth = width))
            val under = 2.dp.roundToPx()
            layout(width, constraints.constrainHeight(a.height + under + b.height)) {
                a.placeRelative(0, 0)
                b.placeRelative(0, a.height + under)
            }
        }
    }
}

/**
 * The start's width when both fit on one line of [width] as they are, [gap] apart, or null
 * when the end has to go under the start. The start gets what the end leaves, which is never
 * less than all of it — so neither is ever broken to make them share.
 */
internal fun sideBySide(width: Int, gap: Int, startWhole: Int, endWhole: Int): Int? {
    val left = width - gap - endWhole
    return if (left >= startWhole) left else null
}

@Composable
fun MeterRow(
    label: String,
    detail: String,
    fraction: Double,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label: $detail" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SplitLine(
            start = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            end = {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tint: Color? = null, filled: Boolean = false) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .background(
                if (filled) color.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared empty-state treatment. It stays in the screen's scroll container at large text sizes. */
@Composable
fun EmptyState(title: String, message: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
                .padding(16.dp).size(28.dp),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(
                if (ok) Color(0xFF34A853) else MaterialTheme.colorScheme.error,
                CircleShape,
            ),
    )
}

/** The Mac's verdict words, in colour. */
@Composable
fun verdictTint(verdict: String): Color = when {
    verdict.contains("comfortable", true) -> Color(0xFF34A853)
    verdict.contains("tight", true) -> Color(0xFFE8A33D)
    verdict.contains("swap", true) || verdict.contains("won't", true) ->
        MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
