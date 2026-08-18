package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.ai.DashboardEngine
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.ParameterTrend
import com.healthdecoder.app.model.TrendDataPoint
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import com.healthdecoder.app.util.TestReference
import kotlinx.coroutines.launch

private val statusHigh = Color(0xFFC62828)
private val statusLow = Color(0xFFE65100)
private val statusNormal = Color(0xFF2E7D32)

private fun parseNum(s: String): Float? {
    val m = Regex("-?\\d+(\\.\\d+)?").find(s) ?: return null
    return m.value.toFloatOrNull()
}

private fun statusColor(status: String, fallback: Color): Color = when (status.lowercase()) {
    "high" -> statusHigh
    "low" -> statusLow
    "normal" -> statusNormal
    else -> fallback
}

private val MONTHS = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Compact date WITH year, e.g. "9 Jul '25". */
private fun shortDate(iso: String): String {
    val parts = iso.split("T")[0].split("-")
    if (parts.size < 3) return iso
    val m = parts[1].toIntOrNull() ?: return iso
    val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
    val yy = parts[0].takeLast(2)
    return "$day ${MONTHS.getOrElse(m) { parts[1] }} '$yy"
}

/** Parses yyyy-MM-dd to epoch millis so points can be spaced by real time. */
private fun isoToMillis(iso: String): Long? = try {
    val parts = iso.split("T")[0].split("-")
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
    cal.timeInMillis
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPatient by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(DashboardEngine.CATEGORY_ALL) }
    var categoryMenu by remember { mutableStateOf(false) }
    var trends by remember { mutableStateOf<List<ParameterTrend>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var patientMenu by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // Build the patient list once.
    LaunchedEffect(Unit) {
        val reports = LocalRepository.getReports(context)
        val byCount = reports.mapNotNull { it.patientName }.groupingBy { it }.eachCount()
        patients = byCount.entries.sortedByDescending { it.value }.map { it.key }
        // Default to the family member selected on Home, if that person has trend data.
        val active = com.healthdecoder.app.local.AppSettings.getActivePatient(context)
        selectedPatient = patients.firstOrNull { it.equals(active, ignoreCase = true) } ?: patients.firstOrNull()
        if (selectedPatient == null) isLoading = false
    }

    // Reload trends when patient/period changes, or the user taps refresh.
    LaunchedEffect(selectedPatient, period, refreshTick) {
        val p = selectedPatient ?: return@LaunchedEffect
        isLoading = true
        trends = try {
            LocalRepository.getHealthSummary(context, p, period).parameterTrends
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
        isLoading = false
    }

    val withData = remember(trends) {
        trends.filter { t -> t.dataPoints.any { parseNum(it.value) != null } }
    }
    // Only offer panels that actually have data, so the dropdown never leads to an empty screen.
    val availableCategories = remember(withData) {
        val present = withData.map { DashboardEngine.categoryOf(it.name) }.toSet()
        listOf(DashboardEngine.CATEGORY_ALL) +
            DashboardEngine.TREND_CATEGORIES.map { it.first }.filter { it in present } +
            listOf(DashboardEngine.CATEGORY_OTHER).filter { it in present }
    }
    val visibleTrends = remember(withData, selectedCategory) {
        if (selectedCategory == DashboardEngine.CATEGORY_ALL) withData
        else withData.filter { DashboardEngine.categoryOf(it.name) == selectedCategory }
    }
    // A previously chosen panel can vanish when the patient/period changes — fall back to All.
    LaunchedEffect(availableCategories) {
        if (selectedCategory !in availableCategories) selectedCategory = DashboardEngine.CATEGORY_ALL
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Column {
                            Text(tr("Health Trends"), fontWeight = FontWeight.Bold)
                            Text(tr("Tap any point to open that report"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Back")) }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }, enabled = !isLoading && selectedPatient != null) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = tr("Refresh trends"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
            )
        },
        bottomBar = {
            AppBottomNavBar(currentTab = BottomNavTab.Trends, onNavigate = onNavigateToTab)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .appWatermark()
        ) {
            // Patient + key-only controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (patients.size > 1) {
                    ExposedDropdownMenuBox(expanded = patientMenu, onExpandedChange = { patientMenu = it }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = selectedPatient ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("Patient")) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patientMenu) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = patientMenu, onDismissRequest = { patientMenu = false }) {
                            patients.forEach { p ->
                                DropdownMenuItem(text = { Text(p) }, onClick = { selectedPatient = p; patientMenu = false })
                            }
                        }
                    }
                } else {
                    Text(selectedPatient ?: "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }

            // Test-panel selector — "All tests" plus every panel that has data for this patient.
            ExposedDropdownMenuBox(
                expanded = categoryMenu,
                onExpandedChange = { categoryMenu = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = selectedCategory,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(tr("Test group")) },
                    leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenu) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    availableCategories.forEach { cat ->
                        val count = if (cat == DashboardEngine.CATEGORY_ALL) withData.size
                                    else withData.count { DashboardEngine.categoryOf(it.name) == cat }
                        DropdownMenuItem(
                            text = { Text(if (count > 0) "$cat  ($count)" else cat) },
                            onClick = { selectedCategory = cat; categoryMenu = false }
                        )
                    }
                }
            }

            // Period chips
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf(null to "All", "3m" to "3M", "6m" to "6M", "1y" to "1Y", "2y" to "2Y")) { (value, label) ->
                    FilterChip(
                        selected = period == value,
                        onClick = { period = value },
                        label = { Text(tr(label), fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    selectedPatient == null -> EmptyStateView(Icons.Default.ShowChart, tr("No reports yet"), tr("Scan lab reports (blood test, thyroid, etc.) and their trends will appear here."))
                    visibleTrends.isEmpty() -> EmptyStateView(Icons.Default.ShowChart, tr("No test values to chart"), tr("Trends appear once you have reports with numeric test values like TSH, sugar, hemoglobin, cholesterol."))
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { OverviewCard(trends = visibleTrends, period = period) }
                        items(visibleTrends) { trend ->
                            TrendCard(trend = trend, onPointClick = { dp -> if (dp.reportId.isNotEmpty()) onNavigateToReport(dp.reportId, trend.name) })
                        }
                    }
                }
            }
        }
    }
}

private fun periodLabel(period: String?): String = when (period) {
    null, "all" -> "all time"
    "1m" -> "last 1 month"
    "3m" -> "last 3 months"
    "6m" -> "last 6 months"
    "1y" -> "last 1 year"
    "2y" -> "last 2 years"
    else -> period
}

@Composable
private fun OverviewCard(trends: List<ParameterTrend>, period: String?) {
    if (trends.isEmpty()) return
    ClinicalSectionCard(
        title = tr("Trends Overview"),
        icon = Icons.Default.Dashboard,
        accentColor = MaterialTheme.colorScheme.primary,
        subtitle = periodLabel(period).replaceFirstChar { it.uppercase() }
    ) {
        trends.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val latest = t.dataPoints.lastOrNull()
                    val arrow = when (t.trend) {
                        "improving", "decreasing" -> "↓"; "worsening", "increasing" -> "↑"; else -> "→"
                    }
                    ResultTile(
                        label = t.name,
                        value = latest?.value ?: "—",
                        unit = latest?.unit ?: "",
                        status = latest?.status ?: "",
                        trendArrow = arrow,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrendCard(trend: ParameterTrend, onPointClick: (TrendDataPoint) -> Unit) {
    val latest = trend.dataPoints.lastOrNull()
    val (trendIcon, trendColor) = when (trend.trend) {
        "improving", "decreasing" -> Icons.Default.TrendingDown to statusNormal
        "worsening", "increasing" -> Icons.Default.TrendingUp to statusHigh
        else -> Icons.Default.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        val info = TestReference.describe(trend.name)
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info?.title ?: trend.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                latest?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${it.value} ${it.unit}".trim() + (it.context.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" } ?: ""),
                            fontWeight = FontWeight.Bold,
                            color = statusColor(it.status, MaterialTheme.colorScheme.onSurface)
                        )
                        Icon(trendIcon, contentDescription = trend.trend, tint = trendColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
            // Latest status as a colored badge + plain-language line, so the number has meaning.
            latest?.status?.takeIf { it.isNotBlank() }?.let { st ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(st)
                    Text(text = tr("Latest reading") +
                            (latest.unit.takeIf { it.isNotBlank() }?.let { " (measured in $it)" } ?: "") +
                            (latest.context.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // The standard unit this test's line is drawn in — the one converted points were
            // brought to, or (if none were converted) the unit shared by the readings.
            val stdUnit = trend.dataPoints.firstOrNull { it.converted }?.unit
                ?: trend.dataPoints.lastOrNull { it.unit.isNotBlank() }?.unit ?: ""
            // Readings in a unit we couldn't safely convert to the standard are kept OFF the line
            // (plotting them on a different scale would distort it) and called out below instead.
            val unconvertible = trend.dataPoints.filter {
                it.unit.isNotBlank() && it.unit != stdUnit && !it.converted
            }
            val chartPoints = trend.dataPoints.filterNot { it in unconvertible }
            val convertedCount = trend.dataPoints.count { it.converted }

            Spacer(Modifier.height(8.dp))
            TrendLineChart(points = chartPoints, onPointClick = onPointClick)
            if (convertedCount > 0) {
                Text(
                    text = "↺ $convertedCount reading(s) from other labs converted to $stdUnit for comparison — tap a point to open the report and see the original.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (unconvertible.isNotEmpty()) {
                val listed = unconvertible.joinToString(", ") { "${it.value} ${it.unit}".trim() }
                Text(
                    text = "⚠ Not shown on the chart (a different unit we can't safely convert to $stdUnit): $listed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusLow,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Plain-language explanation of what this test means (curated, accurate).
            if (info != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun TrendLineChart(points: List<TrendDataPoint>, onPointClick: (TrendDataPoint) -> Unit) {
    val nums = points.map { parseNum(it.value) }
    val validIdx = points.indices.filter { nums[it] != null }
    if (validIdx.isEmpty()) {
        Text(tr("No numeric values to chart."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    // Normal-range band bounds (the reading's healthy range, already in the plotted unit). Use the
    // most recent point that carries a range so the band reflects the current lab's reference.
    val bandLow = points.lastOrNull { it.refLow != null }?.refLow
    val bandHigh = points.lastOrNull { it.refHigh != null }?.refHigh

    // Scale the y-axis to the DATA (not the reference band) so the actual readings fill the chart
    // and their variation is visible; the normal band is drawn as an overlay clipped to the plot,
    // so a wide/far-off range (e.g. a very-low TSH sitting below normal) can't squash the line.
    // Nudge the range just enough toward the nearer band edge that the "Normal" boundary is on
    // screen when it's close, but never let the band dictate the whole scale.
    val dataMin = validIdx.minOf { nums[it]!! }
    val dataMax = validIdx.maxOf { nums[it]!! }
    val span0 = (dataMax - dataMin)
    val pad = if (span0 == 0f) (if (dataMax == 0f) 1f else kotlin.math.abs(dataMax) * 0.15f) else span0 * 0.15f
    var minV = dataMin - pad
    var maxV = dataMax + pad
    // Pull in a band edge only if it's within ~half the data span of the data (so it's "just off
    // screen"), never further — keeps the reference visible without flattening the data.
    val nearPull = (if (span0 == 0f) pad else span0) * 0.6f
    bandLow?.let { if (it < minV && it >= dataMin - nearPull) minV = it - pad * 0.4f }
    bandHigh?.let { if (it > maxV && it <= dataMax + nearPull) maxV = it + pad * 0.4f }
    // Never show a negative axis for a naturally non-negative measurement.
    if (dataMin >= 0f) minV = minV.coerceAtLeast(0f)
    val range = (maxV - minV).let { if (it == 0f) 1f else it }

    // Position points by their real date (full year+month+day), not evenly by index.
    val times = points.map { isoToMillis(it.date) }
    val distinctTimes = validIdx.mapNotNull { times[it] }.distinct()
    val useTime = distinctTimes.size >= 2 && validIdx.all { times[it] != null }
    val minT = distinctTimes.minOrNull() ?: 0L
    val spanT = ((distinctTimes.maxOrNull() ?: 0L) - minT).let { if (it == 0L) 1L else it }

    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bandColor = Color(0xFF2E7D32) // healthy green
    val surface = MaterialTheme.colorScheme.surface
    val normalLabel = tr("Normal") // hoisted: tr() is @Composable, can't run inside Canvas draw

    val labelPaint = remember { Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true } }
    labelPaint.color = labelColor.toArgb()
    labelPaint.textSize = with(density) { 10.sp.toPx() }
    val valuePaint = remember { Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true } }
    valuePaint.color = MaterialTheme.colorScheme.onSurface.toArgb()
    valuePaint.textSize = with(density) { 10.sp.toPx() }
    val axisLabelPaint = remember { Paint().apply { textAlign = Paint.Align.LEFT; isAntiAlias = true } }
    axisLabelPaint.color = labelColor.copy(alpha = 0.7f).toArgb()
    axisLabelPaint.textSize = with(density) { 9.sp.toPx() }

    // Grow the line/fill in from the left, and fade the points/labels in, on first composition.
    val progress by animateFloatAsState(
        targetValue = 1f, animationSpec = tween(700), label = "trendGrow"
    )

    var positions by remember { mutableStateOf<List<Pair<Offset, TrendDataPoint>>>(emptyList()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .pointerInput(points) {
                detectTapGestures { tap ->
                    val hit = positions.minByOrNull { (o, _) -> (o - tap).getDistance() }
                    if (hit != null && (hit.first - tap).getDistance() <= 56f) onPointClick(hit.second)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padL = with(density) { 6.dp.toPx() }
            val padR = with(density) { 6.dp.toPx() }
            val padT = with(density) { 24.dp.toPx() }
            val padB = with(density) { 22.dp.toPx() }
            val w = size.width; val h = size.height
            val n = points.size
            fun xFor(i: Int): Float {
                if (n <= 1) return w / 2f
                return if (useTime) padL + (w - padL - padR) * ((times[i]!! - minT).toFloat() / spanT.toFloat())
                else padL + (w - padL - padR) * i / (n - 1)
            }
            fun yFor(v: Float) = padT + (h - padT - padB) * (1f - (v - minV) / range)

            // Shaded healthy normal-range band (drawn first, behind everything).
            if (bandLow != null || bandHigh != null) {
                val yTop = yFor(bandHigh ?: maxV).coerceIn(padT, h - padB)
                val yBot = yFor(bandLow ?: minV).coerceIn(padT, h - padB)
                drawRect(
                    color = bandColor.copy(alpha = 0.10f),
                    topLeft = Offset(padL, minOf(yTop, yBot)),
                    size = androidx.compose.ui.geometry.Size(w - padL - padR, kotlin.math.abs(yBot - yTop))
                )
                listOfNotNull(bandHigh, bandLow).forEach { b ->
                    val y = yFor(b).coerceIn(padT, h - padB)
                    drawLine(bandColor.copy(alpha = 0.35f), Offset(padL, y), Offset(w - padR, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 3.dp.toPx() })))
                }
                drawContext.canvas.nativeCanvas.drawText(
                    normalLabel, w - padR - with(density) { 2.dp.toPx() },
                    yFor(bandHigh ?: maxV).coerceIn(padT + 8f, h - padB) + with(density) { 9.dp.toPx() },
                    Paint().apply { color = bandColor.copy(alpha = 0.7f).toArgb(); textAlign = Paint.Align.RIGHT; textSize = with(density) { 8.sp.toPx() }; isAntiAlias = true }
                )
            }

            // Light gridlines with value labels.
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 5.dp.toPx() }))
            listOf(0f, 0.5f, 1f).forEach { frac ->
                val y = padT + (h - padT - padB) * frac
                drawLine(axisColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f, pathEffect = dashEffect)
                val value = minV + range * (1f - frac)
                drawContext.canvas.nativeCanvas.drawText("%.1f".format(value), padL, y - with(density) { 3.dp.toPx() }, axisLabelPaint)
            }

            val pos = validIdx.map { i -> Offset(xFor(i), yFor(nums[i]!!)) to points[i] }
            positions = pos

            if (pos.size >= 2) {
                // Smooth the line with cubic segments (Catmull-Rom → Bézier control points).
                val pts = pos.map { it.first }
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[if (i == 0) 0 else i - 1]
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val p3 = pts[if (i + 2 < pts.size) i + 2 else i + 1]
                        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                        cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                    }
                }

                // Gradient fill under the smoothed line.
                val fillPath = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, h - padB)
                    lineTo(pts.first().x, h - padB)
                    close()
                }
                clipRect(left = 0f, top = 0f, right = padL + (w - padL - padR) * progress, bottom = h) {
                    drawPath(
                        fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primary.copy(alpha = 0.28f), primary.copy(alpha = 0.02f)),
                            startY = padT, endY = h - padB
                        ),
                        style = Fill
                    )
                    drawPath(line, color = primary, style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }

            // Points + value labels + spaced date labels (fade in after the line has grown).
            var lastLabelX = -10000f
            val minGap = with(density) { 46.dp.toPx() }
            val dotAlpha = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
            pos.forEach { (o, dp) ->
                val c = statusColor(dp.status, primary)
                drawCircle(color = c.copy(alpha = 0.18f * dotAlpha), radius = with(density) { 9.dp.toPx() }, center = o)
                drawCircle(color = surface, radius = with(density) { 5.dp.toPx() } * dotAlpha, center = o)
                drawCircle(color = c.copy(alpha = dotAlpha), radius = with(density) { 4.dp.toPx() } * dotAlpha, center = o)
                drawCircle(color = Color.White.copy(alpha = dotAlpha), radius = with(density) { 1.5.dp.toPx() } * dotAlpha, center = o)
                if (dotAlpha > 0.05f) {
                    valuePaint.alpha = (255 * dotAlpha).toInt()
                    labelPaint.alpha = (255 * dotAlpha).toInt()
                    val pointLabel = buildString {
                        append(dp.value)
                        if (dp.context.isNotBlank()) append(" (${dp.context.first()})")
                        if (dp.converted) append(" ↺")
                    }
                    drawContext.canvas.nativeCanvas.drawText(pointLabel, o.x, o.y - with(density) { 11.dp.toPx() }, valuePaint)
                    if (o.x - lastLabelX >= minGap) {
                        drawContext.canvas.nativeCanvas.drawText(shortDate(dp.date), o.x, h - with(density) { 6.dp.toPx() }, labelPaint)
                        lastLabelX = o.x
                    }
                }
            }
        }
    }
}
