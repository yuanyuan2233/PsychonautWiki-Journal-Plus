/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaakhanimann.journal.data.substances.classes.ActiveMetabolite
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import com.isaakhanimann.journal.data.substances.classes.ReceptorTarget
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import com.isaakhanimann.journal.ui.theme.horizontalPadding
import com.isaakhanimann.journal.ui.theme.verticalPaddingCards
import java.util.Locale
import kotlin.math.abs

/**
 * 临床药代动力学 (PK / ADME) 与药效学靶点谱 (PD) 专属展示组件：
 * 支持母体 + 活性代谢物串联链、单次/稳态双轨药代动力学曲线、
 * 临床洗脱期校准（覆盖不可逆酶抑制与长效代谢物）及受体靶点亲和力可视化。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PharmacokineticsView(
    pk: Pharmacokinetics,
    substanceName: String? = null,
) {
    val isZh = SubstanceLocalizationHelper.isChinese()

    Column {
        // 1. 原研商品名标牌 (若适用)
        if (pk.brandName != null) {
            ElevatedCard(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding, vertical = verticalPaddingCards)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isZh) "原研参考: ${pk.brandName}" else "Reference Brand: ${pk.brandName}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 2. 药代动力学 (PK / ADME) 核心看板
        val hasAdmeMetrics = pk.halfLife != null ||
            pk.halfLifeHours != null ||
            pk.tMax != null ||
            pk.tMaxHours != null ||
            pk.bioavailability != null ||
            pk.cypEnzymes.isNotEmpty() ||
            pk.activeMetabolites.isNotEmpty() ||
            pk.washoutHours != null ||
            pk.washoutNote != null

        if (hasAdmeMetrics) {
            SectionWithTitle(title = if (isZh) "药代动力学 (PK / ADME)" else "Pharmacokinetics (PK / ADME)") {
                Column(
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding, vertical = 6.dp)
                        .fillMaxWidth()
                ) {
                    if (pk.pkRole != null) {
                        PkRoleBadge(pkRole = pk.pkRole, isZh = isZh)
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    PkMetricGrid(pk = pk, isZh = isZh)
                    PkTextNotesBox(pk = pk, isZh = isZh)

                    // 活性代谢物转化链
                    if (pk.activeMetabolites.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        ActiveMetaboliteChainSection(
                            pk = pk,
                            substanceName = substanceName,
                            isZh = isZh
                        )
                    }

                    // 母体 + 活性代谢物双轨动力学演化曲线
                    if ((pk.halfLifeHours ?: 0.0) > 0.0 || (pk.resolvedEffectiveHalfLifeHours ?: 0.0) > 0.0) {
                        Spacer(modifier = Modifier.height(14.dp))
                        DualTrackPkChartSection(
                            pk = pk,
                            substanceName = substanceName,
                            isZh = isZh
                        )
                    }

                    // 体内代谢消除稳态时程预测 + 临床洗脱期
                    val effectiveHl = pk.resolvedEffectiveHalfLifeHours
                    if ((effectiveHl != null && effectiveHl > 0.0) || pk.washoutHours != null || pk.washoutNote != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        PkEliminationSection(pk = pk, effectiveHalfLifeHours = effectiveHl, isZh = isZh)
                    }

                    // CYP450 代谢酶谱芯片组
                    if (pk.cypEnzymes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isZh) "主要代谢途径 (CYP450 酶谱)" else "Primary Metabolic Pathways (CYP450)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pk.cypEnzymes.forEach { enzyme ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            text = enzyme,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 清除与排泄机制说明
                    if (pk.clearance != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isZh) "排泄途径: ${pk.clearance}" else "Clearance: ${pk.clearance}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // 3. 药效学机制与受体靶点谱 (PD)
        if (pk.mechanismSummary != null || pk.receptorTargets.isNotEmpty()) {
            SectionWithTitle(title = if (isZh) "药效学机制与受体靶点谱 (PD)" else "Pharmacodynamics & Receptor Targets (PD)") {
                ReceptorTargetSection(pk = pk, isZh = isZh)
            }
        }

        // 4. 临床适应症
        if (pk.indications.isNotEmpty()) {
            SectionWithTitle(title = if (isZh) "临床适应症" else "Clinical Indications") {
                Column(
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding, vertical = 6.dp)
                        .fillMaxWidth()
                ) {
                    pk.indications.forEach { ind ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ind,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // 5. 禁忌症与用药安全警示卡片
        if (pk.contraindications.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding, vertical = verticalPaddingCards)
                    .fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding, vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isZh) "禁忌症与用药安全警示" else "Contraindications & Safety Warnings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    pk.contraindications.forEach { contra ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = contra,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PkMetricSpec(
    val title: String,
    val value: String,
    val subtitle: String,
    val icon: ImageVector,
    val highlightTertiary: Boolean = false,
)

@Composable
private fun PkRoleBadge(pkRole: String, isZh: Boolean) {
    val (label, containerColor, contentColor) = when (pkRole) {
        "ACTIVE_METABOLITE" -> Triple(
            if (isZh) "动力学特征：活性代谢物接力主导" else "Kinetic Profile: Active Metabolite Relay",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        "PRODRUG" -> Triple(
            if (isZh) "动力学特征：前药经酶活化发挥药效 (Prodrug)" else "Kinetic Profile: Prodrug Bioactivation",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        "IRREVERSIBLE_PD" -> Triple(
            if (isZh) "动力学特征：不可逆靶点灭活（生物洗脱期远超血浆 t½）" else "Kinetic Profile: Irreversible Target Inactivation (PD > PK)",
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.onErrorContainer
        )
        "DEPOT_FLIP_FLOP" -> Triple(
            if (isZh) "动力学特征：长效储库缓释（翻转动力学 Flip-Flop）" else "Kinetic Profile: Long-Acting Depot (Flip-Flop Kinetics)",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> Triple(
            if (isZh) "动力学特征：原型药物直接主导" else "Kinetic Profile: Parent-Dominant",
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Biotech,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun PkMetricGrid(pk: Pharmacokinetics, isZh: Boolean) {
    val parentHl = pk.halfLifeHours?.takeIf { it > 0.0 }
    val effHl = pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 }
    val showDualHalfLife = parentHl != null && effHl != null && effHl > parentHl * 1.15

    val cards = buildList {
        if (pk.halfLife != null || parentHl != null) {
            add(
                PkMetricSpec(
                    title = if (showDualHalfLife) {
                        if (isZh) "母体 t½" else "Parent t½"
                    } else {
                        if (isZh) "消除半衰期 (t½)" else "Half-life (t½)"
                    },
                    value = parentHl?.let { formatHours(it, isZh) } ?: compactMetricText(pk.halfLife.orEmpty()),
                    subtitle = if (showDualHalfLife) {
                        if (isZh) "原型血浆消除" else "Parent clearance"
                    } else {
                        if (isZh) "血药浓度减半" else "Plasma halving"
                    },
                    icon = Icons.Default.Timer,
                )
            )
        }
        if (showDualHalfLife) {
            add(
                PkMetricSpec(
                    title = if (isZh) "总活性终末 t½" else "Active Moiety t½",
                    value = formatHours(effHl!!, isZh),
                    subtitle = if (isZh) "含活性代谢物" else "Incl. metabolites",
                    icon = Icons.Default.Timer,
                    highlightTertiary = true,
                )
            )
        }
        if (pk.tMax != null || pk.tMaxHours != null) {
            add(
                PkMetricSpec(
                    title = if (isZh) "达峰时间 (Tmax)" else "Tmax",
                    value = pk.tMaxHours?.let { "${formatShortNumber(it)}h" } ?: compactMetricText(pk.tMax.orEmpty()),
                    subtitle = if (isZh) "峰值血药浓度" else "Peak level",
                    icon = Icons.Default.Speed,
                )
            )
        }
        if (pk.bioavailability != null) {
            add(
                PkMetricSpec(
                    title = if (isZh) "生物利用度 (F)" else "Bioavailability",
                    value = compactMetricText(pk.bioavailability),
                    subtitle = if (isZh) "系统循环吸收" else "Systemic fraction",
                    icon = Icons.Default.Science,
                )
            )
        }
    }

    if (cards.isEmpty()) return

    val rows = if (cards.size <= 3) listOf(cards) else cards.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowCards.forEach { spec ->
                    PkMetricCard(spec = spec, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PkMetricCard(
    spec: PkMetricSpec,
    modifier: Modifier = Modifier,
) {
    val accent = if (spec.highlightTertiary) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = if (spec.highlightTertiary) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f))
        } else {
            CardDefaults.outlinedCardBorder()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = spec.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = spec.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = spec.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PkTextNotesBox(pk: Pharmacokinetics, isZh: Boolean) {
    val notes = buildList {
        val hlText = pk.halfLife?.trim().orEmpty()
        if (hlText.isNotEmpty()) {
            val formattedNumeric = pk.halfLifeHours?.let { formatHours(it, isZh) }
            if (hlText != formattedNumeric) {
                add((if (isZh) "半衰期详情：" else "Half-life detail: ") + hlText)
            }
        }
        val tMaxText = pk.tMax?.trim().orEmpty()
        if (tMaxText.isNotEmpty()) {
            val compactTMax = pk.tMaxHours?.let { "${formatShortNumber(it)}h" }
            if (tMaxText != compactTMax) {
                add((if (isZh) "达峰时间详情：" else "Tmax detail: ") + tMaxText)
            }
        }
        val bioText = pk.bioavailability?.trim().orEmpty()
        if (bioText.isNotEmpty() && bioText != compactMetricText(bioText)) {
            add((if (isZh) "生物利用度说明：" else "Bioavailability detail: ") + bioText)
        }
    }

    if (notes.isEmpty()) return

    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            notes.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActiveMetaboliteChainSection(
    pk: Pharmacokinetics,
    substanceName: String?,
    isZh: Boolean,
) {
    Text(
        text = if (isZh) "母体 → 活性代谢物转化链" else "Parent → Active Metabolite Cascade",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))

    // 母体起点节点
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = substanceName ?: if (isZh) "母体化合物" else "Parent Drug",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (pk.pkRole) {
                        "PRODRUG" -> if (isZh) "前药原型（需经体内代谢转化为活性分子）" else "Prodrug (requires metabolic bioactivation)"
                        else -> if (isZh) "母体活性原型" else "Parent active compound"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            pk.halfLifeHours?.takeIf { it > 0 }?.let { hl ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "t½ ${formatHours(hl, isZh)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }

    pk.activeMetabolites.forEach { met ->
        MetaboliteStepCard(metabolite = met, isZh = isZh)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaboliteStepCard(metabolite: ActiveMetabolite, isZh: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.width(6.dp))
        val pathwayText = metabolite.pathway ?: if (isZh) "体内代谢转化" else "Metabolic conversion"
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        ) {
            Text(
                text = pathwayText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = metabolite.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val hlBadge = metabolite.halfLifeHours?.takeIf { it > 0 }?.let { "t½ ${formatHours(it, isZh)}" }
                    ?: metabolite.halfLife
                if (hlBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiary
                    ) {
                        Text(
                            text = hlBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                val expBadge = metabolite.relativeExposure
                    ?: metabolite.exposureRatio?.let { r ->
                        if (isZh) "稳态暴露 ≈ 母体 ${formatShortNumber(r)}×" else "SS exposure ≈ ${formatShortNumber(r)}× parent"
                    }
                if (expBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = expBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (!metabolite.halfLife.isNullOrBlank() && metabolite.halfLifeHours != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = (if (isZh) "消除半衰期：" else "Half-life: ") + metabolite.halfLife,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!metabolite.pharmacologicalRole.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = metabolite.pharmacologicalRole,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DualTrackPkChartSection(
    pk: Pharmacokinetics,
    substanceName: String?,
    isZh: Boolean,
) {
    var mode by remember(pk) {
        val defaultSteady = pk.pkRole == "ACTIVE_METABOLITE" || (pk.resolvedEffectiveHalfLifeHours ?: 0.0) >= 48.0
        mutableStateOf(if (defaultSteady) PkChartMode.STEADY_STATE else PkChartMode.SINGLE_DOSE)
    }

    val parentLabel = if (pk.pkRole == "PRODRUG") {
        if (isZh) "${substanceName ?: "母体"} (前药原型)" else "${substanceName ?: "Parent"} (Prodrug)"
    } else {
        if (isZh) "${substanceName ?: "母体"} (原型)" else "${substanceName ?: "Parent"}"
    }
    val totalLabel = if (isZh) "总活性组分暴露" else "Total Active Moiety"

    val sim = remember(pk, mode, parentLabel, totalLabel) {
        PkCurveCalculator.simulate(
            pk = pk,
            parentLabel = parentLabel,
            totalLabel = totalLabel,
            mode = mode
        )
    } ?: return

    Text(
        text = if (isZh) "血药暴露动力学模拟曲线" else "Simulated Concentration-Time Profile",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == PkChartMode.SINGLE_DOSE,
            onClick = { mode = PkChartMode.SINGLE_DOSE },
            label = { Text(if (isZh) "单次给药轨迹" else "Single Dose") }
        )
        FilterChip(
            selected = mode == PkChartMode.STEADY_STATE,
            onClick = { mode = PkChartMode.STEADY_STATE },
            label = { Text(if (isZh) "连续稳态蓄积" else "Steady-State") }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val metPalette = listOf(
        tertiaryColor,
        Color(0xFFE67E22),
        Color(0xFF00897B),
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val steadyLineColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(165.dp)
            ) {
                val padLeft = 6.dp.toPx()
                val padRight = 6.dp.toPx()
                val padTop = 10.dp.toPx()
                val padBottom = 8.dp.toPx()
                val plotW = (size.width - padLeft - padRight).coerceAtLeast(1f)
                val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)
                val horizon = sim.totalHorizonHours.coerceAtLeast(1.0)

                // 背景水平与垂直参考网格
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val y = padTop + plotH * (1f - frac)
                    drawLine(
                        color = gridColor,
                        start = Offset(padLeft, y),
                        end = Offset(padLeft + plotW, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                    val x = padLeft + plotW * frac
                    drawLine(
                        color = gridColor.copy(alpha = 0.35f),
                        start = Offset(x, padTop),
                        end = Offset(x, padTop + plotH),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 稳态预计达成标线
                sim.steadyStateHoursEstimate?.let { ssHours ->
                    val xFrac = (ssHours / horizon).toFloat().coerceIn(0f, 1f)
                    if (xFrac in 0.05f..0.98f) {
                        val sx = padLeft + plotW * xFrac
                        drawLine(
                            color = steadyLineColor,
                            start = Offset(sx, padTop),
                            end = Offset(sx, padTop + plotH),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        )
                    }
                }

                var metIndex = 0
                // 先绘制包络与母体，再绘制代谢物保证清晰可读
                sim.series.forEach { series ->
                    if (series.points.size < 2) return@forEach
                    val color = when {
                        series.isTotalActive -> primaryColor
                        series.isParent -> if (pk.activeMetabolites.isEmpty()) primaryColor else secondaryColor
                        else -> {
                            val c = metPalette[metIndex % metPalette.size]
                            metIndex++
                            c
                        }
                    }

                    val linePath = Path()
                    val fillPath = Path()
                    series.points.forEachIndexed { idx, (t, yNorm) ->
                        val px = padLeft + plotW * (t / horizon).toFloat().coerceIn(0f, 1f)
                        val py = padTop + plotH * (1f - yNorm.toFloat().coerceIn(0f, 1f))
                        if (idx == 0) {
                            linePath.moveTo(px, py)
                            fillPath.moveTo(px, padTop + plotH)
                            fillPath.lineTo(px, py)
                        } else {
                            linePath.lineTo(px, py)
                            fillPath.lineTo(px, py)
                        }
                        if (idx == series.points.lastIndex) {
                            fillPath.lineTo(px, padTop + plotH)
                            fillPath.close()
                        }
                    }

                    if (series.isTotalActive || (series.isParent && pk.activeMetabolites.isEmpty())) {
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)),
                                startY = padTop,
                                endY = padTop + plotH
                            )
                        )
                    }

                    val pathEffect = when {
                        series.isParent && pk.pkRole == "PRODRUG" -> PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                        series.isTotalActive -> null
                        else -> null
                    }
                    val strokeW = when {
                        series.isTotalActive -> 2.8.dp.toPx()
                        series.isMetabolite -> 2.4.dp.toPx()
                        else -> 2.0.dp.toPx()
                    }
                    drawPath(
                        path = linePath,
                        color = color,
                        style = Stroke(
                            width = strokeW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = pathEffect
                        )
                    )
                }
            }

            // 时间横轴刻度
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatHours(sim.totalHorizonHours * 0.5, isZh),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatHours(sim.totalHorizonHours, isZh),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 曲线图例
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var legendMetIdx = 0
                sim.series.forEach { series ->
                    val color = when {
                        series.isTotalActive -> primaryColor
                        series.isParent -> if (pk.activeMetabolites.isEmpty()) primaryColor else secondaryColor
                        else -> {
                            val c = metPalette[legendMetIdx % metPalette.size]
                            legendMetIdx++
                            c
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = series.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 动力学说明脚注
            val caption = when (mode) {
                PkChartMode.SINGLE_DOSE -> if (isZh) {
                    "单次给药相对 AUC 暴露模拟（覆盖 5× 终末半衰期，完整体现代谢物接力与长尾效应）"
                } else {
                    "Single-dose relative AUC trajectory over 5× terminal t½"
                }
                PkChartMode.STEADY_STATE -> {
                    val tauStr = sim.dosingIntervalHours?.let { formatHours(it, isZh) } ?: "24h"
                    val ssStr = sim.steadyStateHoursEstimate?.let { formatHours(it, isZh) } ?: ""
                    if (isZh) {
                        "按每 $tauStr 重复给药蓄积模拟；红色虚线为预计稳态平台（约 $ssStr）"
                    } else {
                        "Repeated dosing every $tauStr; red dashed line marks ~steady state ($ssStr)"
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PkEliminationSection(
    pk: Pharmacokinetics,
    effectiveHalfLifeHours: Double?,
    isZh: Boolean,
) {
    Text(
        text = if (isZh) "体内代谢消除与安全洗脱期" else "Elimination & Clinical Washout",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (effectiveHalfLifeHours != null && effectiveHalfLifeHours > 0.0) {
                val parentHl = pk.halfLifeHours
                val drivenByMetabolite = parentHl != null && effectiveHalfLifeHours > parentHl * 1.15
                if (drivenByMetabolite) {
                    Text(
                        text = if (isZh) {
                            "按最长活性组分终末半衰期 (${formatHours(effectiveHalfLifeHours, isZh)}) 推算："
                        } else {
                            "Calculated from longest active moiety (t½ ≈ ${formatHours(effectiveHalfLifeHours, isZh)}):"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isZh) "消除阶段 (一级动力学)" else "Elimination Stage (1st-Order)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isZh) "剩余活性浓度" else "Remaining Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                EliminationStepRow(step = "1× t½ (${formatHours(effectiveHalfLifeHours * 1, isZh)})", percent = 50f, remaining = "50%")
                EliminationStepRow(step = "2× t½ (${formatHours(effectiveHalfLifeHours * 2, isZh)})", percent = 25f, remaining = "25%")
                EliminationStepRow(step = "3× t½ (${formatHours(effectiveHalfLifeHours * 3, isZh)})", percent = 12.5f, remaining = "12.5%")
                EliminationStepRow(
                    step = "5× t½ (${formatHours(effectiveHalfLifeHours * 5, isZh)})",
                    percent = 3.1f,
                    remaining = if (isZh) "< 3.2% (药代清除)" else "< 3.2% (PK Cleared)"
                )

                val totalClearHours = effectiveHalfLifeHours * 5.0
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isZh) {
                            "活性组分完全药代清除 (~5×t½)：约 ${formatHoursLong(totalClearHours, isZh)}"
                        } else {
                            "Estimated active-moiety clearance (~5× t½): ${formatHoursLong(totalClearHours, isZh)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (pk.washoutHours != null || !pk.washoutNote.isNullOrBlank()) {
                if (effectiveHalfLifeHours != null && effectiveHalfLifeHours > 0.0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val titleText = buildString {
                                append(if (isZh) "临床安全洗脱 / 生物效应期" else "Clinical Washout / Biological Recovery")
                                pk.washoutHours?.takeIf { it > 0.0 }?.let { h ->
                                    append("：${formatHoursLong(h, isZh)}")
                                }
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!pk.washoutNote.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pk.washoutNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EliminationStepRow(step: String, percent: Float, remaining: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = step, style = MaterialTheme.typography.bodySmall)
            Text(text = remaining, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceptorTargetSection(pk: Pharmacokinetics, isZh: Boolean) {
    Column(
        modifier = Modifier
            .padding(horizontal = horizontalPadding, vertical = 6.dp)
            .fillMaxWidth()
    ) {
        if (!pk.mechanismSummary.isNullOrBlank()) {
            Text(
                text = pk.mechanismSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (pk.receptorTargets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        pk.receptorTargets.forEachIndexed { idx, target ->
            ReceptorTargetRow(target = target, isZh = isZh)
            if (idx < pk.receptorTargets.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceptorTargetRow(target: ReceptorTarget, isZh: Boolean) {
    val isMetaboliteDriven = target.dominantActor?.let {
        it.contains("代谢物") || it.contains("metabolite", ignoreCase = true) ||
            it.contains("DDCAR", ignoreCase = true) || it.contains("O-DSMT", ignoreCase = true) ||
            it.contains("去甲") || it.contains("坎利酮") || it.contains("MHD")
    } == true

    val actionLower = target.action.lowercase(Locale.ROOT)
    val (actionBg, actionFg) = when {
        actionLower.contains("不可逆") || actionLower.contains("irreversible") ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        actionLower.contains("拮抗") || actionLower.contains("antagonist") ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        actionLower.contains("部分激动") || actionLower.contains("partial") ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = target.target,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(shape = RoundedCornerShape(10.dp), color = actionBg) {
                    Text(
                        text = target.action,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = actionFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (!target.dominantActor.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isMetaboliteDriven) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Text(
                            text = target.dominantActor,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMetaboliteDriven) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = { target.affinityWeight.coerceIn(0.1f, 1.0f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isMetaboliteDriven) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                if (!target.affinityTier.isNullOrBlank()) {
                    Text(
                        text = target.affinityTier,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = if (isZh) "相对强度 ${(target.affinityWeight * 100).toInt()}%" else "${(target.affinityWeight * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun compactMetricText(raw: String): String {
    val trimmed = raw.trim()
    val parenIdx = trimmed.indexOfAny(charArrayOf('(', '（', ';', '；', ','))
    val base = if (parenIdx > 0) trimmed.substring(0, parenIdx).trim() else trimmed
    return if (base.length > 14) base.take(13) + "…" else base
}

private fun formatShortNumber(value: Double): String {
    return if (abs(value - value.toLong()) < 0.05) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
}

private fun formatHours(hours: Double, isZh: Boolean): String {
    return if (hours >= 48.0) {
        val days = hours / 24.0
        if (isZh) String.format(Locale.ROOT, "%.1f天", days) else String.format(Locale.ROOT, "%.1fd", days)
    } else {
        String.format(Locale.ROOT, "%.1fh", hours)
    }
}

private fun formatHoursLong(hours: Double, isZh: Boolean): String {
    return if (hours >= 48.0) {
        if (isZh) {
            String.format(Locale.ROOT, "%.1f 天 (%.0f 小时)", hours / 24.0, hours)
        } else {
            String.format(Locale.ROOT, "%.1f d (%.0f h)", hours / 24.0, hours)
        }
    } else {
        if (isZh) {
            String.format(Locale.ROOT, "%.1f 小时", hours)
        } else {
            String.format(Locale.ROOT, "%.1f h", hours)
        }
    }
}
