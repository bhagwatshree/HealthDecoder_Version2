package com.healthdecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared design language for "clinical panel" screens (Doctor Brief, Trends, Report Detail,
 * Reminders, Records) — sectioned cards with an icon-in-circle header, and consistent
 * High/Low/Normal/Worsened/Improved color coding. Reuses the exact status colors already used
 * ad hoc across the app (TrendsScreen, ReportDetailScreen, TodaysMedicinesTab) so this isn't a
 * new palette, just a shared, named version of the one already in use.
 */
object ClinicalStatus {
    val High = Color(0xFFC62828)
    val Low = Color(0xFFE65100)
    val Normal = Color(0xFF2E7D32)
    val Neutral = Color(0xFF546E7A)

    fun colorFor(status: String): Color = when (status.trim().lowercase()) {
        "high", "worsened", "poor", "serious" -> High
        "low", "borderline", "moderate", "soon" -> Low
        "normal", "improved", "good", "completed" -> Normal
        else -> Neutral
    }

    /**
     * One letter for a lab result's status, for the results table where the badge shares a row
     * with the name, value and reference range. Spelled out, "NORMAL" was wide enough to wrap
     * inside its own pill — rendering as "NORM AL", and on a narrow row as a column of single
     * letters — so the colour, which is the part actually read at a glance, arrived attached to
     * unreadable text. Returns null for a status with no accepted abbreviation, so the caller can
     * fall back to the full word rather than invent a letter for it.
     */
    fun shortLabelFor(status: String): String? = when (status.trim().lowercase()) {
        "high" -> "H"
        "low" -> "L"
        "normal" -> "N"
        else -> null
    }
}

/**
 * A theme-safe tinted background for a "status card" (e.g. a green/red/orange insight card) —
 * blends [color] at low alpha OVER the current theme's surface, so it reads correctly in both
 * light and dark. A hardcoded pale hex (e.g. `Color(0xFFE8F5E9)`) stays pale even in dark theme,
 * which — combined with `MaterialTheme.colorScheme.onSurface` text (white in dark theme) — makes
 * the card's text nearly invisible. This fixes that without abandoning the colored-card look.
 */
@Composable
fun statusContainerColor(color: Color, alpha: Float = 0.14f): Color =
    color.copy(alpha = alpha).compositeOver(MaterialTheme.colorScheme.surface)

/**
 * Small colored pill for a status word ("HIGH", "LOW", "WORSENED", "COMPLETED", ...).
 *
 * [compact] shortens High/Low/Normal to H/L/N for tight rows such as the lab results table, where
 * the pill competes with the parameter name, value and reference range for width. The colour is
 * unchanged, and a status with no accepted abbreviation still shows its full word.
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color = ClinicalStatus.colorFor(text),
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val label = (if (compact) ClinicalStatus.shortLabelFor(text) else null) ?: text.uppercase()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = if (compact) 7.dp else 8.dp, vertical = 3.dp)
    ) {
        Text(
            label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = color, letterSpacing = 0.3.sp,
            maxLines = 1
        )
    }
}

/**
 * A titled card section with an icon-in-circle header — the repeating "panel" shape used across
 * Doctor Brief / Trends / Report Detail / Reminders / Records for one clearly labeled group of
 * related data (e.g. "ACTIVE MEDICATION SCHEDULE", "ABNORMAL RESULTS").
 */
@Composable
fun ClinicalSectionCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurface
                    )
                    subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

/** One tile in a results/vitals grid: label, big value+unit, and a status word if not normal. */
@Composable
fun ResultTile(
    label: String,
    value: String,
    unit: String = "",
    status: String = "",
    trendArrow: String? = null,
    modifier: Modifier = Modifier
) {
    val color = ClinicalStatus.colorFor(status)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, fontWeight = FontWeight.Medium
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            if (unit.isNotBlank()) Text(unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trendArrow?.let { Text(it, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold) }
        }
        if (status.isNotBlank() && status.lowercase() != "normal" && status.lowercase() != "stable") {
            Text(status.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** One row inside a schedule/list section: bold title, secondary meta line, optional trailing badge/chip. */
@Composable
fun ClinicalRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}
