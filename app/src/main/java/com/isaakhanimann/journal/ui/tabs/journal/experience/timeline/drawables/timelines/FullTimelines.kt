/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.drawables.timelines

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDuration
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.WeightedLine
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.drawables.TimelineDrawable
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.ingestionDotRadius
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.normalStroke
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.shapeAlpha
import com.isaakhanimann.journal.ui.tabs.journal.experience.timeline.strokeWidth
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class FullTimelines(
    val onset: FullDurationRange,
    val comeup: FullDurationRange,
    val peak: FullDurationRange,
    val offset: FullDurationRange,
    val weightedLines: List<WeightedLine>,
    val startTimeGraph: Instant,
) : TimelineDrawable {

    data class WeightedLineRelativeToFirst(
        val startTimeRelativeToGroupInSeconds: Float,
        val horizontalWeight: Float,
        val height: Float
    )

    data class LineSegment(
        val start: Point,
        val end: Point
    ) {
        fun isInside(x: Float): Boolean {
            return start.x <= x && x < end.x
        }

        fun heightAt(x: Float): Float {
            val divider = end.x - start.x
            if (!divider.isFinite() || divider <= 0f) return 0f
            val m = (end.y - start.y) / divider
            val b = start.y - m * start.x
            val result = m * x + b
            return if (result.isFinite()) result else 0f
        }
    }

    data class Point(
        val x: Float,
        val y: Float,
    )

    data class FinalPoint(
        val x: Float,
        val y: Float,
        val isIngestionPoint: Boolean = false
    )

    private val finalPoints: List<FinalPoint>

    override var referenceHeight: Float = 1f

    override val nonNormalisedHeight: Float

    override val endOfLineRelativeToStartInSeconds: Float

    init {
        val (rangeLines, pointLines) = weightedLines.partition { weightedLine ->
            val endTime = weightedLine.endTime ?: return@partition false
            val startX = Duration.between(startTimeGraph, weightedLine.startTime).seconds.toFloat()
            val endX = Duration.between(startTimeGraph, endTime).seconds.toFloat()
            startX.isFinite() && endX.isFinite() && startX < endX
        }
        val timeRangeLineSegments = rangeLines.flatMap {
            return@flatMap getLineSegments(weightedLine = it)
        }
        val weightedRelatives = pointLines.map {
            WeightedLineRelativeToFirst(
                startTimeRelativeToGroupInSeconds = Duration.between(
                    startTimeGraph,
                    it.startTime
                ).seconds.toFloat(),
                horizontalWeight = it.horizontalWeight,
                height = if (it.height.isFinite()) it.height else 0f
            )
        }
        val lineSegments = weightedRelatives.flatMap { relative ->
            val result = mutableListOf<LineSegment>()
            val onsetAndComeupWeight = 0.5f
            val onsetEndX =
                relative.startTimeRelativeToGroupInSeconds + onset.interpolateAtValueInSeconds(
                    onsetAndComeupWeight
                )
            val comeupStartPoint = Point(x = onsetEndX, y = 0f)
            val comeupEndX =
                onsetEndX + comeup.interpolateAtValueInSeconds(onsetAndComeupWeight)
            val peakStartPoint = Point(x = comeupEndX, y = relative.height)
            result.add(
                LineSegment(
                    start = comeupStartPoint,
                    end = peakStartPoint
                )
            )
            val peakEndX =
                comeupEndX + (peak.interpolateAtValueInSeconds(relative.horizontalWeight))
            val peakEndPoint = Point(x = peakEndX, y = relative.height)
            result.add(
                LineSegment(
                    start = peakStartPoint,
                    end = peakEndPoint
                )
            )
            val offsetEndX =
                peakEndX + offset.interpolateAtValueInSeconds(relative.horizontalWeight)
            val offsetEndPoint = Point(x = offsetEndX, y = 0f)
            result.add(
                LineSegment(
                    start = peakEndPoint,
                    end = offsetEndPoint
                )
            )
            return@flatMap result
        } + timeRangeLineSegments
        val linePoints = lineSegments.flatMap { lineSegment ->
            listOf(lineSegment.start.x, lineSegment.end.x)
        }.distinct().map { FinalPoint(x = it, y = 0f) }
        val ingestionPoints = weightedRelatives.map {
            FinalPoint(
                x = it.startTimeRelativeToGroupInSeconds,
                y = 0f,
                isIngestionPoint = true
            )
        }
        val pointsToConsider = ingestionPoints + linePoints
        val pointsWithHeight = pointsToConsider.map { finalPoint ->
            val x = finalPoint.x
            val sumOfHeights = lineSegments.sumOf { lineSegment ->
                val height = if (lineSegment.isInside(x)) lineSegment.heightAt(x) else 0f
                if (height.isFinite()) height.toDouble() else 0.0
            }.toFloat()
            return@map FinalPoint(
                x = x,
                y = if (sumOfHeights.isFinite()) sumOfHeights else 0f,
                isIngestionPoint = finalPoint.isIngestionPoint
            )
        }.filter { it.x.isFinite() && it.y.isFinite() }
        val sortedPoints = pointsWithHeight.sortedBy { it.x }
        this.finalPoints = sortedPoints
        this.nonNormalisedHeight = (sortedPoints.maxOfOrNull { it.y }?.takeIf { it.isFinite() } ?: 0.01f)
            .coerceAtLeast(0.01f)
        val fallbackEndOfLine =
            onset.maxInSeconds + comeup.maxInSeconds + peak.maxInSeconds + offset.maxInSeconds
        this.endOfLineRelativeToStartInSeconds =
            finalPoints.maxOfOrNull { it.x }?.takeIf { it.isFinite() }
                ?: if (fallbackEndOfLine.isFinite()) fallbackEndOfLine else 0f
    }

    private fun getLineSegments(weightedLine: WeightedLine): List<LineSegment> {
        if (weightedLine.endTime == null) {
            return emptyList()
        }
        val startX =
            Duration.between(startTimeGraph, weightedLine.startTime).seconds.toFloat()
        val endX =
            Duration.between(startTimeGraph, weightedLine.endTime).seconds.toFloat()
        val rangeInSeconds = endX - startX
        val onsetInSeconds = onset.interpolateAtValueInSeconds(0.5f)
        val comeupInSeconds = comeup.interpolateAtValueInSeconds(0.5f)

        var horizontalWeightToUse = 0.5f
        if (rangeInSeconds < peak.minInSeconds) {
            // if the range is short enough we use the same duration as for point ingestion
            horizontalWeightToUse = weightedLine.horizontalWeight
        }
        val peakInSeconds = peak.interpolateAtValueInSeconds(horizontalWeightToUse)
        val offsetInSeconds = offset.interpolateAtValueInSeconds(horizontalWeightToUse)

        val points = getSamplePoints(
            startX = startX,
            endX = endX,
            hMax = weightedLine.height,
            onset = onsetInSeconds,
            comeup = comeupInSeconds,
            peak = peakInSeconds,
            offset = offsetInSeconds
        )
        val lineSegments = getLineSegments(points)

        return lineSegments
    }

    companion object {

        fun getLineSegments(points: List<Point>): List<LineSegment> {
            if (points.isEmpty()) {
                return emptyList()
            }
            val result: MutableList<LineSegment> = mutableListOf()
            var previousPoint = points.first()
            for (currentPoint in points.takeLast(points.size - 1)) {
                result.add(
                    LineSegment(
                        start = previousPoint,
                        end = currentPoint
                    )
                )
                previousPoint = currentPoint
            }
            return result
        }

        fun getSamplePoints(
            startX: Float,
            endX: Float,
            hMax: Float,
            onset: Float,
            comeup: Float,
            peak: Float,
            offset: Float,
        ): List<Point> {
            if (!startX.isFinite() || !endX.isFinite() || !hMax.isFinite() ||
                !onset.isFinite() || !comeup.isFinite() || !peak.isFinite() || !offset.isFinite() ||
                startX >= endX
            ) {
                return emptyList()
            }
            val safeOnset = onset.coerceAtLeast(0f)
            val safeComeup = comeup.coerceAtLeast(0f)
            val safePeak = peak.coerceAtLeast(0f)
            val safeOffset = offset.coerceAtLeast(0f)

            val numberOfSteps = 30
            val startSampleRange = startX + safeOnset
            val endSampleRange = endX + safeOnset + safeComeup + safePeak + safeOffset
            val stepSize = (endSampleRange - startSampleRange) / numberOfSteps
            if (!stepSize.isFinite() || stepSize <= 0f) {
                return emptyList()
            }

            val points = (1..<numberOfSteps).map { step ->
                val x = startSampleRange + step * stepSize
                val height = if (safeComeup > 0f && safeOffset > 0f) {
                    calculateExpression(
                        x = x,
                        startX = startX,
                        endX = endX,
                        hMax = hMax,
                        onset = safeOnset,
                        comeup = safeComeup,
                        peak = safePeak,
                        offset = safeOffset
                    )
                } else {
                    calculateStepOrTrapezoidAverage(
                        x = x,
                        startX = startX,
                        endX = endX,
                        hMax = hMax,
                        onset = safeOnset,
                        comeup = safeComeup,
                        peak = safePeak,
                        offset = safeOffset
                    )
                }
                return@map Point(x = x, y = if (height.isFinite()) height else 0f)
            }
            val firstPoint = Point(x = startSampleRange, y = 0f)
            val lastPoint = Point(x = endSampleRange, y = 0f)

            return (listOf(firstPoint) + points + listOf(lastPoint))
                .filter { it.x.isFinite() && it.y.isFinite() }
        }

        private fun calculateExpression(
            x: Float,
            startX: Float,
            endX: Float,
            hMax: Float,
            onset: Float,
            comeup: Float,
            peak: Float,
            offset: Float,
        ): Float {
            val term1 = 2 * comeup * offset * (
                    min(endX, max(startX, -comeup - onset + x)) -
                            min(endX, max(startX, -comeup - onset - peak + x))
                    )

            val term2 = 2 * comeup * (
                    min(endX, max(startX, -comeup - onset - peak + x)) -
                            min(endX, max(startX, -comeup - offset - onset - peak + x))
                    ) * (comeup + offset + onset + peak - x)

            val term3 = comeup * (
                    min(endX, max(startX, -comeup - onset - peak + x)).pow(2) -
                            min(endX, max(startX, -comeup - offset - onset - peak + x)).pow(2)
                    )

            val term4 = 2 * offset * (onset - x) * (
                    -min(endX, max(startX, -onset + x)) +
                            min(endX, max(startX, -comeup - onset + x))
                    )

            val term5 = offset * (
                    -min(endX, max(startX, -onset + x)).pow(2) +
                            min(endX, max(startX, -comeup - onset + x)).pow(2)
                    )

            val numerator = 0.5f * hMax * (term1 + term2 + term3 + term4 + term5)
            val denominator = comeup * offset * (endX - startX)
            if (!denominator.isFinite() || denominator <= 0f) {
                return 0f
            }

            val result = numerator / denominator
            return if (result.isFinite()) result else 0f
        }

        private fun calculateStepOrTrapezoidAverage(
            x: Float,
            startX: Float,
            endX: Float,
            hMax: Float,
            onset: Float,
            comeup: Float,
            peak: Float,
            offset: Float,
        ): Float {
            val span = endX - startX
            if (!span.isFinite() || span <= 0f) return 0f
            val a = x - endX
            val b = x - startX
            val t0 = onset
            val t1 = t0 + comeup
            val t2 = t1 + peak
            val t3 = t2 + offset

            var area = 0.0
            if (comeup > 0f) {
                val l = max(a, t0)
                val r = min(b, t1)
                if (r > l) {
                    val hl = hMax.toDouble() * ((l - t0) / comeup)
                    val hr = hMax.toDouble() * ((r - t0) / comeup)
                    area += 0.5 * (hl + hr) * (r - l)
                }
            }
            if (peak > 0f) {
                val l = max(a, t1)
                val r = min(b, t2)
                if (r > l) {
                    area += hMax.toDouble() * (r - l)
                }
            }
            if (offset > 0f) {
                val l = max(a, t2)
                val r = min(b, t3)
                if (r > l) {
                    val hl = hMax.toDouble() * (1.0 - (l - t2) / offset)
                    val hr = hMax.toDouble() * (1.0 - (r - t2) / offset)
                    area += 0.5 * (hl + hr) * (r - l)
                }
            }
            val result = (area / span).toFloat()
            return if (result.isFinite()) result else 0f
        }
    }

    override fun drawTimeLine(
        drawScope: DrawScope,
        canvasHeight: Float,
        pixelsPerSec: Float,
        color: Color,
        density: Density
    ) {
        if (!canvasHeight.isFinite() || canvasHeight <= 0f ||
            !pixelsPerSec.isFinite() || pixelsPerSec <= 0f
        ) {
            return
        }
        val safeReferenceHeight = if (referenceHeight.isFinite() && referenceHeight > 0f) {
            referenceHeight
        } else {
            1f
        }
        val finalPointsNormalised = finalPoints
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map {
                FinalPoint(
                    x = it.x,
                    y = it.y / safeReferenceHeight,
                    isIngestionPoint = it.isIngestionPoint
                )
            }
        val firstPoint = finalPointsNormalised.firstOrNull() ?: return
        val lastPoint = finalPointsNormalised.lastOrNull() ?: return
        val path = Path().apply {
            val rest = finalPointsNormalised.drop(1)
            val firstHeightInPx = firstPoint.y * canvasHeight
            moveTo(x = firstPoint.x * pixelsPerSec, y = canvasHeight - firstHeightInPx)
            for (point in rest) {
                val heightInPx = point.y * canvasHeight
                lineTo(x = point.x * pixelsPerSec, y = canvasHeight - heightInPx)
            }
        }
        drawScope.drawPath(
            path = path,
            color = color,
            style = density.normalStroke
        )
        path.lineTo(
            x = lastPoint.x * pixelsPerSec,
            y = canvasHeight + drawScope.strokeWidth / 2
        )
        path.lineTo(
            x = firstPoint.x * pixelsPerSec,
            y = canvasHeight + drawScope.strokeWidth / 2
        )
        path.close()
        drawScope.drawPath(
            path = path,
            color = color.copy(alpha = shapeAlpha)
        )
        for (point in finalPointsNormalised) {
            if (point.isIngestionPoint) {
                drawScope.drawCircle(
                    color = color,
                    radius = density.ingestionDotRadius,
                    center = Offset(
                        x = point.x * pixelsPerSec,
                        y = canvasHeight - point.y * canvasHeight
                    )
                )
            }
        }
    }
}

fun RoaDuration.toFullTimelines(
    weightedLines: List<WeightedLine>,
    startTimeGraph: Instant,
): FullTimelines? {
    val fullOnset = onset?.toFullDurationRange()
    val fullComeup = comeup?.toFullDurationRange()
    val fullPeak = peak?.toFullDurationRange()
    val fullOffset = offset?.toFullDurationRange()
    return if (fullOnset != null && fullComeup != null && fullPeak != null && fullOffset != null) {
        FullTimelines(
            onset = fullOnset,
            comeup = fullComeup,
            peak = fullPeak,
            offset = fullOffset,
            weightedLines = weightedLines,
            startTimeGraph = startTimeGraph,
        )
    } else {
        null
    }

}