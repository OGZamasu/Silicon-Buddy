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
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                if (footnote != null) {
                    Text(
                        footnote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
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
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
