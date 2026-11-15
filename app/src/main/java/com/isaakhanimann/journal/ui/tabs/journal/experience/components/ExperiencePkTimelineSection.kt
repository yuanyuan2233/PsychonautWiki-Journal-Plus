/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.journal.experience.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.substances.CypInteractionEffectType
import com.isaakhanimann.journal.data.substances.EnzymaticDdiFinding
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import com.isaakhanimann.journal.ui.tabs.search.substance.ExperienceLivePkSimulation
import com.isaakhanimann.journal.ui.tabs.search.substance.PkPoint
import com.isaakhanimann.journal.ui.tabs.search.substance.SubstanceLivePkProfile
import com.isaakhanimann.journal.ui.theme.horizontalPadding
import com.isaakhanimann.journal.ui.utils.getLocalDateTime
import com.isaakhanimann.journal.ui.utils.getStringOfPattern
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ExperiencePkTimelineCard(
    simulation: ExperienceLivePkSimulation,
    firstIngestionTime: Instant,
    timeDisplayOption: TimeDisplayOption,
    verticalCardPadding: Dp,
    includeCarryover: Boolean = true,
    onToggleIncludeCarryover: (Boolean) -> Unit = {},
) {
    val isZh = SubstanceLocalizationHelper.isChinese()
    ElevatedCard(modifier = Modifier.padding(vertical = verticalCardPadding)) {
        CardTitle(
            title = if (includeCarryover) {
                if (isZh) "血药与活性代谢物实况 (跨日卷积)" else "Live Plasma & Active Metabolites (42d)"
            } else {
                if (isZh) "血药与活性代谢物实况 (仅本次体验)" else "Live Plasma & Active Metabolites (Current)"
            }
        )
        HorizontalDivider()
        ExperiencePkTimelineContent(
            simulation = simulation,
            firstIngestionTime = firstIngestionTime,
            timeDisplayOption = timeDisplayOption,
            modifier = Modifier
                .padding(horizontal = horizontalPadding, vertical = 10.dp)
                .fillMaxWidth(),
            includeCarryover = includeCarryover,
            onToggleIncludeCarryover = onToggleIncludeCarryover
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExperiencePkTimelineContent(
    simulation: ExperienceLivePkSimulation,
    firstIngestionTime: Instant,
    timeDisplayOption: TimeDisplayOption,
    modifier: Modifier = Modifier,
    includeCarryover: Boolean = true,
    onToggleIncludeCarryover: (Boolean) -> Unit = {},
) {
    val profiles = simulation.profiles
    if (profiles.isEmpty() && includeCarryover) return

    val isZh = SubstanceLocalizationHelper.isChinese()
    val isDarkTheme = isSystemInDarkTheme()

    // 单一物质默认直接进入详细分解模式（母体 vs 各活性代谢物 vs 总活性暴露），多物质默认全部叠加
    var selectedCanonical by remember(profiles.map { it.canonicalName }) {
        mutableStateOf(if (profiles.size == 1) profiles.first().canonicalName else null)
    }

    LaunchedEffect(profiles) {
        if (selectedCanonical != null && profiles.none { it.canonicalName == selectedCanonical }) {
            selectedCanonical = if (profiles.size == 1) profiles.first().canonicalName else null
        }
    }

    val selectedProfile = profiles.firstOrNull { it.canonicalName == selectedCanonical }

    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val metPalette = remember(tertiaryColor) {
        listOf(
            tertiaryColor,
            Color(0xFFE67E22),
            Color(0xFF00897B),
        )
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val nowLineColor = MaterialTheme.colorScheme.error.copy(alpha = 0.78f)
    val baselineCurveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 顶部引导行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (includeCarryover) {
                        if (isZh) "跨日血药与活性代谢物实况 (42天回溯)" else "Cross-Day Plasma & Active Metabolites (42d)"
                    } else {
                        if (isZh) "本次体验实时血药与代谢物实况" else "Current Experience Plasma & Metabolites"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 一键切换：含 42 天历史残留 / 仅看本次体验
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = includeCarryover,
                onClick = { if (!includeCarryover) onToggleIncludeCarryover(true) },
                label = {
                    Text(
                        text = stringResource(R.string.journal_pk_carryover_included),
                        maxLines = 1
                    )
                }
            )
            FilterChip(
                selected = !includeCarryover,
                onClick = { if (includeCarryover) onToggleIncludeCarryover(false) },
                label = {
                    Text(
                        text = stringResource(R.string.journal_pk_current_only),
                        maxLines = 1
                    )
                }
            )
        }

        if (profiles.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.journal_pk_current_only_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            return
        }

        // 物质切换 Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (profiles.size > 1) {
                FilterChip(
                    selected = selectedCanonical == null,
                    onClick = { selectedCanonical = null },
                    label = {
                        Text(
                            text = if (isZh) "全部叠加 (${profiles.size})" else "Overlay (${profiles.size})",
                            maxLines = 1
                        )
                    }
                )
            }
            profiles.forEach { profile ->
                val badge = buildString {
                    append(profile.displayName)
                    if (profile.currentIngestionCount == 0 && profile.carryoverIngestionCount > 0) {
                        append(if (isZh) " · 跨日驻留" else " · Carryover")
                    } else if (profile.carryoverIngestionCount > 0) {
                        append(if (isZh) " · 含历史蓄积" else " · +Prior")
                    }
                }
                FilterChip(
                    selected = selectedCanonical == profile.canonicalName,
                    onClick = { selectedCanonical = profile.canonicalName },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(profile.color.getComposeColor(isDarkTheme))
                        )
                    },
                    label = {
                        Text(
                            text = badge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        // 选中单物质的跨日蓄积状态与双量纲胶囊
        if (selectedProfile != null) {
            ProfileCarryoverBanner(profile = selectedProfile, isZh = isZh)
        } else {
            val carryoverProfiles = profiles.filter { it.carryoverIngestionCount > 0 }
            if (carryoverProfiles.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isZh) {
                            val totalPrior = carryoverProfiles.sumOf { it.carryoverIngestionCount }
                            val maxDays = carryoverProfiles.maxOf { it.lookbackDaysMax }
                            "已叠加前 $maxDays 天内共 $totalPrior 次尚未排空的历史给药及其活性代谢物（点击上方药名查看母体与代谢物分层明细）"
                        } else {
                            val totalPrior = carryoverProfiles.sumOf { it.carryoverIngestionCount }
                            val maxDays = carryoverProfiles.maxOf { it.lookbackDaysMax }
                            "Includes $totalPrior unwashed prior doses from the past ${maxDays}d (tap a substance chip for parent/metabolite breakdown)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            OverlayDualScaleBanner(profiles = profiles)
        }

        // CYP450 酶动力学 DDI 修正对比提示条
        val visibleFindings = if (selectedProfile != null) {
            selectedProfile.ddiFindings
        } else {
            profiles.flatMap { it.ddiFindings }
        }.distinctBy {
            "${it.perpetratorName.lowercase(Locale.ROOT)}|${it.victimName.lowercase(Locale.ROOT)}|${it.enzyme.lowercase(Locale.ROOT)}"
        }
        if (visibleFindings.isNotEmpty()) {
            EnzymaticDdiFindingsBanner(findings = visibleFindings, isZh = isZh)
        }

        // 实时卷积曲线画布
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                val winStart = simulation.windowStartEpochSecond
                val winEnd = simulation.windowEndEpochSecond
                val totalHorizon = ((winEnd - winStart).coerceAtLeast(3600L)) / 3600.0
                val nowEpoch = simulation.currentTimeEpochSecond

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp)
                ) {
                    val padLeft = 6.dp.toPx()
                    val padRight = 6.dp.toPx()
                    val padTop = 10.dp.toPx()
                    val padBottom = 10.dp.toPx()
                    val plotW = (size.width - padLeft - padRight).coerceAtLeast(1f)
                    val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)

                    // 参考水平网格 (0%, 50%, 100%)
                    listOf(0f, 0.5f, 1f).forEach { frac ->
                        val y = padTop + plotH * (1f - frac)
                        drawLine(
                            color = gridColor,
                            start = Offset(padLeft, y),
                            end = Offset(padLeft + plotW, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    // 参考垂直网格 (25%, 50%, 75%)
                    listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                        val x = padLeft + plotW * frac
                        drawLine(
                            color = gridColor.copy(alpha = 0.38f),
                            start = Offset(x, padTop),
                            end = Offset(x, padTop + plotH),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // 绘制曲线
                    val baselineDashPattern = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    if (selectedProfile != null) {
                        val baseColor = selectedProfile.color.getComposeColor(isDarkTheme)
                        val hasMetabolites = selectedProfile.metaboliteSeries.isNotEmpty()
                        val dashPattern = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))

                        if (selectedProfile.unadjustedTotalActivePoints.size >= 2) {
                            drawPkSeriesPath(
                                points = selectedProfile.unadjustedTotalActivePoints,
                                horizonHours = totalHorizon,
                                padLeft = padLeft,
                                padTop = padTop,
                                plotW = plotW,
                                plotH = plotH,
                                color = baselineCurveColor,
                                strokeWidth = 1.7.dp.toPx(),
                                withGradientFill = false,
                                pathEffect = baselineDashPattern
                            )
                        }

                        if (hasMetabolites) {
                            drawPkSeriesPath(
                                points = selectedProfile.totalActivePoints,
                                horizonHours = totalHorizon,
                                padLeft = padLeft,
                                padTop = padTop,
                                plotW = plotW,
                                plotH = plotH,
                                color = baseColor,
                                strokeWidth = 2.9.dp.toPx(),
                                withGradientFill = true
                            )
                        }

                        val parentColor = if (hasMetabolites) secondaryColor else baseColor
                        val isProdrug = selectedProfile.pk.pkRole == "PRODRUG"
                        drawPkSeriesPath(
                            points = selectedProfile.parentPoints,
                            horizonHours = totalHorizon,
                            padLeft = padLeft,
                            padTop = padTop,
                            plotW = plotW,
                            plotH = plotH,
                            color = parentColor,
                            strokeWidth = if (hasMetabolites) 2.0.dp.toPx() else 2.8.dp.toPx(),
                            withGradientFill = !hasMetabolites,
                            pathEffect = if (isProdrug) dashPattern else null
                        )

                        selectedProfile.metaboliteSeries.forEachIndexed { idx, met ->
                            val metColor = metPalette[idx % metPalette.size]
                            drawPkSeriesPath(
                                points = met.points,
                                horizonHours = totalHorizon,
                                padLeft = padLeft,
                                padTop = padTop,
                                plotW = plotW,
                                plotH = plotH,
                                color = metColor,
                                strokeWidth = 2.3.dp.toPx(),
                                withGradientFill = false,
                                pathEffect = dashPattern
                            )
                        }

                        // 标出窗口内真实给药时间点
                        selectedProfile.doseEventsInWindow.forEach { ev ->
                            val xFrac = ((ev.timeEpochSecond - winStart) / 3600.0 / totalHorizon).toFloat().coerceIn(0f, 1f)
                            val cx = padLeft + plotW * xFrac
                            drawCircle(
                                color = baseColor,
                                radius = 4.dp.toPx(),
                                center = Offset(cx, padTop + plotH)
                            )
                        }
                    } else {
                        val dashPattern = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                        profiles.forEach { profile ->
                            val subColor = profile.color.getComposeColor(isDarkTheme)
                            if (profile.unadjustedTotalActivePoints.size >= 2) {
                                drawPkSeriesPath(
                                    points = profile.unadjustedTotalActivePoints,
                                    horizonHours = totalHorizon,
                                    padLeft = padLeft,
                                    padTop = padTop,
                                    plotW = plotW,
                                    plotH = plotH,
                                    color = baselineCurveColor,
                                    strokeWidth = 1.5.dp.toPx(),
                                    withGradientFill = false,
                                    pathEffect = baselineDashPattern
                                )
                            }
                            drawPkSeriesPath(
                                points = profile.totalActivePoints,
                                horizonHours = totalHorizon,
                                padLeft = padLeft,
                                padTop = padTop,
                                plotW = plotW,
                                plotH = plotH,
                                color = subColor,
                                strokeWidth = 2.6.dp.toPx(),
                                withGradientFill = true,
                                fillAlpha = 0.11f
                            )
                            // 同时将代表性主要活性代谢物以虚线叠画在下面以便对比
                            val primaryMet = profile.metaboliteSeries.maxByOrNull { it.halfLifeHours * it.exposureRatio }
                            if (primaryMet != null) {
                                drawPkSeriesPath(
                                    points = primaryMet.points,
                                    horizonHours = totalHorizon,
                                    padLeft = padLeft,
                                    padTop = padTop,
                                    plotW = plotW,
                                    plotH = plotH,
                                    color = subColor.copy(alpha = 0.85f),
                                    strokeWidth = 1.7.dp.toPx(),
                                    withGradientFill = false,
                                    pathEffect = dashPattern
                                )
                            }
                            profile.doseEventsInWindow.forEach { ev ->
                                val xFrac = ((ev.timeEpochSecond - winStart) / 3600.0 / totalHorizon).toFloat().coerceIn(0f, 1f)
                                val cx = padLeft + plotW * xFrac
                                drawCircle(
                                    color = subColor,
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(cx, padTop + plotH)
                                )
                            }
                        }
                    }

                    // “现在”时间竖直虚线
                    if (nowEpoch != null && nowEpoch in winStart..winEnd) {
                        val nowFrac = ((nowEpoch - winStart).toDouble() / (winEnd - winStart).coerceAtLeast(1L)).toFloat().coerceIn(0.01f, 0.99f)
                        val nx = padLeft + plotW * nowFrac
                        drawLine(
                            color = nowLineColor,
                            start = Offset(nx, padTop),
                            end = Offset(nx, padTop + plotH),
                            strokeWidth = 1.6.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                        )
                        drawCircle(
                            color = nowLineColor,
                            radius = 3.5.dp.toPx(),
                            center = Offset(nx, padTop)
                        )
                    }
                }

                // 时间轴标签（与顶部时间显示选项联动）
                Spacer(modifier = Modifier.height(4.dp))
                val midEpoch = winStart + (winEnd - winStart) / 2L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatWindowTimeLabel(
                            epochSecond = winStart,
                            firstIngestionTime = firstIngestionTime,
                            timeDisplayOption = timeDisplayOption,
                            nowEpochSecond = nowEpoch,
                            isZh = isZh
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatWindowTimeLabel(
                            epochSecond = midEpoch,
                            firstIngestionTime = firstIngestionTime,
                            timeDisplayOption = timeDisplayOption,
                            nowEpochSecond = nowEpoch,
                            isZh = isZh
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatWindowTimeLabel(
                            epochSecond = winEnd,
                            firstIngestionTime = firstIngestionTime,
                            timeDisplayOption = timeDisplayOption,
                            nowEpochSecond = nowEpoch,
                            isZh = isZh
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 图例说明
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (selectedProfile != null) {
                        val baseColor = selectedProfile.color.getComposeColor(isDarkTheme)
                        val hasMetabolites = selectedProfile.metaboliteSeries.isNotEmpty()
                        val eqHint = selectedProfile.currentActiveEquivalentMg?.let { " · ≈ ${formatEquivalentDoseMg(it)}" } ?: ""
                        if (hasMetabolites) {
                            PkLegendChip(
                                color = baseColor,
                                isDashed = false,
                                text = if (isZh) {
                                    "总活性暴露${selectedProfile.currentRelativeActiveLevel?.let { " · 现在 ${(it * 100).roundToInt()}%" } ?: ""}$eqHint"
                                } else {
                                    "Total Active${selectedProfile.currentRelativeActiveLevel?.let { " · Now ${(it * 100).roundToInt()}%" } ?: ""}$eqHint"
                                }
                            )
                        }
                        val pColor = if (hasMetabolites) secondaryColor else baseColor
                        val parentNowHint = if (!hasMetabolites) {
                            selectedProfile.currentRelativeActiveLevel?.let { " · ${(it * 100).roundToInt()}%" } ?: ""
                        } else {
                            ""
                        }
                        val parentEqHint = if (!hasMetabolites) eqHint else ""
                        PkLegendChip(
                            color = pColor,
                            isDashed = selectedProfile.pk.pkRole == "PRODRUG",
                            text = if (isZh) {
                                "${selectedProfile.displayName}(母体)$parentNowHint$parentEqHint"
                            } else {
                                "${selectedProfile.displayName} (Parent)$parentNowHint$parentEqHint"
                            }
                        )
                        selectedProfile.metaboliteSeries.forEachIndexed { idx, met ->
                            val metColor = metPalette[idx % metPalette.size]
                            val hlText = formatCompactHours(met.halfLifeHours, isZh)
                            val nowText = met.currentNormalizedValue?.let { " · ${(it * 100).roundToInt()}%" } ?: ""
                            PkLegendChip(
                                color = metColor,
                                isDashed = true,
                                text = "${met.name} (t½ $hlText)$nowText"
                            )
                        }
                        if (selectedProfile.unadjustedTotalActivePoints.size >= 2) {
                            PkLegendChip(
                                color = baselineCurveColor,
                                isDashed = true,
                                text = stringResource(R.string.journal_pk_unadjusted_baseline)
                            )
                        }
                    } else {
                        profiles.forEach { profile ->
                            val subColor = profile.color.getComposeColor(isDarkTheme)
                            val primaryMet = profile.metaboliteSeries.maxByOrNull { it.halfLifeHours * it.exposureRatio }
                            val metHint = primaryMet?.let { " + ${it.name}" } ?: ""
                            val nowHint = profile.currentRelativeActiveLevel?.let { " (${(it * 100).roundToInt()}%)" } ?: ""
                            val eqHint = profile.currentActiveEquivalentMg?.let { " · ${formatEquivalentDoseMg(it)}" } ?: ""
                            PkLegendChip(
                                color = subColor,
                                isDashed = false,
                                text = "${profile.displayName}$metHint$nowHint$eqHint"
                            )
                        }
                        if (profiles.any { it.unadjustedTotalActivePoints.size >= 2 }) {
                            PkLegendChip(
                                color = baselineCurveColor,
                                isDashed = true,
                                text = stringResource(R.string.journal_pk_unadjusted_baseline)
                            )
                        }
                    }

                    if (nowEpoch != null && nowEpoch in winStart..winEnd) {
                        PkLegendChip(
                            color = nowLineColor,
                            isDashed = true,
                            text = if (isZh) "此刻" else "Now"
                        )
                    }
                }
            }
        }

        val hasDdiBaseline = profiles.any { it.unadjustedTotalActivePoints.size >= 2 }
        Text(
            text = when {
                includeCarryover && isZh -> {
                    val baselineNote = if (hasDdiBaseline) "，灰细虚线=未受联用影响标准曲线" else ""
                    "注：按实际服药时间自动追溯过去42天内未排空剂量卷积推算（实线=总活性，虚线=活性代谢物$baselineNote）；仅供参考，个体代谢存在差异。"
                }
                includeCarryover -> {
                    val baselineNote = if (hasDdiBaseline) ", fine dashed = unadjusted DDI baseline" else ""
                    "Note: Multi-dose Bateman convolution across the past 42 days (solid = total active, dashed = active metabolites$baselineNote). Individual clearance varies."
                }
                isZh -> {
                    val baselineNote = if (hasDdiBaseline) "，灰细虚线=未受联用影响标准曲线" else ""
                    "注：当前仅基于本次体验给药记录推算（已排除跨日历史残留；实线=总活性，虚线=活性代谢物$baselineNote），仅供参考。"
                }
                else -> {
                    val baselineNote = if (hasDdiBaseline) ", fine dashed = unadjusted DDI baseline" else ""
                    "Note: Simulated from doses in this experience only (42d carryover excluded; solid = total active, dashed = active metabolites$baselineNote)."
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCarryoverBanner(
    profile: SubstanceLivePkProfile,
    isZh: Boolean,
) {
    val startPct = (profile.carryoverBaselineFractionAtStart * 100f).roundToInt()
    val nowPct = profile.currentRelativeActiveLevel?.let { (it * 100f).roundToInt() }
    val showSteadyGain = profile.windowPeakRelativeToSingleDose >= 1.25f
    val currentEq = profile.currentActiveEquivalentMg
    val peakEq = profile.peakActiveEquivalentMg

    if (profile.carryoverIngestionCount == 0 &&
        nowPct == null &&
        !showSteadyGain &&
        currentEq == null &&
        peakEq == null
    ) return

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (profile.currentIngestionCount == 0) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (profile.carryoverIngestionCount > 0) {
                val prefix = if (profile.currentIngestionCount == 0) {
                    if (isZh) "跨日体内驻留 · " else "Carryover in body · "
                } else {
                    ""
                }
                Text(
                    text = if (isZh) {
                        "${prefix}已叠加前 ${profile.lookbackDaysMax} 天 ${profile.carryoverIngestionCount} 次历史给药（窗口起点残余底数 $startPct%）"
                    } else {
                        "${prefix}Stacked ${profile.carryoverIngestionCount} prior doses over ${profile.lookbackDaysMax}d (start baseline $startPct%)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (showSteadyGain) {
                val factorStr = String.format(Locale.ROOT, "%.1f", profile.windowPeakRelativeToSingleDose)
                Text(
                    text = if (isZh) "蓄积倍率 ≈ ${factorStr}× 单次峰" else "Accumulation ≈ ${factorStr}× single dose",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (nowPct != null) {
                Text(
                    text = if (isZh) "此刻活性水平: $nowPct%" else "Current active level: $nowPct%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (currentEq != null) {
                Text(
                    text = stringResource(
                        R.string.journal_pk_active_equivalent_now,
                        formatEquivalentDoseMg(currentEq)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (peakEq != null) {
                Text(
                    text = stringResource(
                        R.string.journal_pk_active_equivalent_peak,
                        formatEquivalentDoseMg(peakEq)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayDualScaleBanner(
    profiles: List<SubstanceLivePkProfile>,
) {
    val dualScaleProfiles = profiles.filter {
        it.currentActiveEquivalentMg != null || it.peakActiveEquivalentMg != null
    }
    if (dualScaleProfiles.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            dualScaleProfiles.forEach { profile ->
                val nowStr = profile.currentActiveEquivalentMg?.let {
                    stringResource(R.string.journal_pk_active_equivalent_now, formatEquivalentDoseMg(it))
                }
                val peakStr = profile.peakActiveEquivalentMg?.let {
                    stringResource(R.string.journal_pk_active_equivalent_peak, formatEquivalentDoseMg(it))
                }
                val combined = listOfNotNull(nowStr, peakStr).joinToString(" · ")
                Text(
                    text = "${profile.displayName}: $combined",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnzymaticDdiFindingsBanner(
    findings: List<EnzymaticDdiFinding>,
    isZh: Boolean,
) {
    if (findings.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.journal_cyp_ddi_section_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            findings.forEach { finding ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (isZh) finding.summaryZh else finding.summaryEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val effectLabel = when (finding.effectType) {
                            CypInteractionEffectType.INHIBITION -> if (isZh) "${finding.enzyme} 抑制" else "${finding.enzyme} inhibition"
                            CypInteractionEffectType.INDUCTION -> if (isZh) "${finding.enzyme} 诱导" else "${finding.enzyme} induction"
                        }
                        Text(
                            text = effectLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (abs(finding.halfLifeMultiplier - 1.0) >= 0.05) {
                            Text(
                                text = stringResource(
                                    R.string.journal_pk_ddi_halflife_multiplier,
                                    formatMultiplier(finding.halfLifeMultiplier)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (abs(finding.activeMetaboliteMultiplier - 1.0) >= 0.05) {
                            Text(
                                text = stringResource(
                                    R.string.journal_pk_ddi_metabolite_multiplier,
                                    formatMultiplier(finding.activeMetaboliteMultiplier)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (finding.isProdrugActivationBlocked) {
                            Text(
                                text = stringResource(R.string.journal_pk_ddi_prodrug_blocked),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PkLegendChip(
    color: Color,
    isDashed: Boolean,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isDashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(color)
                )
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(color)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPkSeriesPath(
    points: List<PkPoint>,
    horizonHours: Double,
    padLeft: Float,
    padTop: Float,
    plotW: Float,
    plotH: Float,
    color: Color,
    strokeWidth: Float,
    withGradientFill: Boolean,
    fillAlpha: Float = 0.20f,
    pathEffect: PathEffect? = null,
) {
    if (points.size < 2 || horizonHours <= 0.0) return
    val linePath = Path()
    val fillPath = Path()
    points.forEachIndexed { idx, pt ->
        val xFrac = (pt.hoursFromStart / horizonHours.toFloat()).coerceIn(0f, 1f)
        val yFrac = pt.normalizedValue.coerceIn(0f, 1f)
        val px = padLeft + plotW * xFrac
        val py = padTop + plotH * (1f - yFrac)
        if (idx == 0) {
            linePath.moveTo(px, py)
            fillPath.moveTo(px, padTop + plotH)
            fillPath.lineTo(px, py)
        } else {
            linePath.lineTo(px, py)
            fillPath.lineTo(px, py)
        }
        if (idx == points.lastIndex) {
            fillPath.lineTo(px, padTop + plotH)
            fillPath.close()
        }
    }
    if (withGradientFill) {
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0.01f)),
                startY = padTop,
                endY = padTop + plotH
            )
        )
    }
    drawPath(
        path = linePath,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = pathEffect
        )
    )
}

private fun formatWindowTimeLabel(
    epochSecond: Long,
    firstIngestionTime: Instant,
    timeDisplayOption: TimeDisplayOption,
    nowEpochSecond: Long?,
    isZh: Boolean,
): String {
    val instant = Instant.ofEpochSecond(epochSecond)
    return when (timeDisplayOption) {
        TimeDisplayOption.RELATIVE_TO_START -> {
            val diffH = (epochSecond - firstIngestionTime.epochSecond) / 3600.0
            if (abs(diffH) < 0.05) "0h"
            else if (diffH > 0) String.format(Locale.ROOT, "+%.1fh", diffH)
            else String.format(Locale.ROOT, "%.1fh", diffH)
        }
        TimeDisplayOption.RELATIVE_TO_NOW -> {
            val nowSec = nowEpochSecond ?: Instant.now().epochSecond
            val diffH = (epochSecond - nowSec) / 3600.0
            when {
                abs(diffH) < 0.15 -> if (isZh) "现在" else "Now"
                diffH > 0 -> String.format(Locale.ROOT, "+%.1fh", diffH)
                else -> String.format(Locale.ROOT, "%.1fh", diffH)
            }
        }
        else -> {
            val dt = instant.getLocalDateTime()
            val firstDt = firstIngestionTime.getLocalDateTime()
            if (dt.toLocalDate() == firstDt.toLocalDate()) {
                instant.getStringOfPattern("HH:mm")
            } else {
                instant.getStringOfPattern("MM/dd HH:mm")
            }
        }
    }
}

private fun formatCompactHours(hours: Double, isZh: Boolean): String {
    return if (hours >= 48.0) {
        val days = hours / 24.0
        if (isZh) String.format(Locale.ROOT, "%.1f天", days) else String.format(Locale.ROOT, "%.1fd", days)
    } else {
        String.format(Locale.ROOT, "%.1fh", hours)
    }
}

private fun formatEquivalentDoseMg(mg: Double): String {
    return when {
        mg <= 1e-6 -> "0 mg"
        mg < 0.001 -> String.format(Locale.ROOT, "%.2f µg", mg * 1000.0)
        mg < 0.1 -> String.format(Locale.ROOT, "%.1f µg", mg * 1000.0)
        mg < 1.0 -> String.format(Locale.ROOT, "%.0f µg", mg * 1000.0)
        mg < 1000.0 -> String.format(Locale.ROOT, "%.1f mg", mg)
        else -> String.format(Locale.ROOT, "%.0f mg", mg)
    }
}

private fun formatMultiplier(multiplier: Double): String {
    return if (multiplier >= 10.0) {
        String.format(Locale.ROOT, "%.1f", multiplier)
    } else {
        String.format(Locale.ROOT, "%.2f", multiplier)
    }
}
