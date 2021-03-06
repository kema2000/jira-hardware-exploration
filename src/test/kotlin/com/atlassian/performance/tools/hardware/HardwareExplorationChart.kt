package com.atlassian.performance.tools.hardware

import com.amazonaws.services.ec2.model.InstanceType
import com.atlassian.performance.tools.io.api.ensureParentDirectory
import com.atlassian.performance.tools.lib.chart.Chart
import com.atlassian.performance.tools.lib.chart.ChartLine
import com.atlassian.performance.tools.lib.chart.ErrorBar
import com.atlassian.performance.tools.lib.chart.Point
import com.atlassian.performance.tools.lib.chart.color.Color
import com.atlassian.performance.tools.lib.chart.color.PresetLabelColor
import com.atlassian.performance.tools.workspace.api.git.GitRepo
import org.apache.logging.log4j.LogManager
import java.math.BigDecimal
import java.math.RoundingMode.HALF_UP
import java.nio.file.Path

internal class HardwareExplorationChart(
    private val repo: GitRepo
) {
    private val logger = LogManager.getLogger(this::class.java)
    private val adgSecondaryPalette = listOf(
        Color(255, 86, 48),
        Color(255, 171, 0),
        Color(54, 179, 126),
        Color(0, 184, 217),
        Color(101, 84, 192)
    )
    private val presetLabelColor: PresetLabelColor = PresetLabelColor(adgSecondaryPalette)

    fun plot(
        results: List<HardwareExplorationResult>,
        application: String,
        output: Path,
        instanceTypeOrder: Comparator<InstanceType>
    ) {
        val resultsPerInstanceType = results
            .mapNotNull { it.testResult }
            .groupBy { it.hardware.instanceType }
            .toSortedMap(instanceTypeOrder)
            .mapValues { (_, testResults) -> testResults.sortedBy { it.hardware.nodeCount } }
        val report = HardwareExplorationChart::class
            .java
            .getResourceAsStream("/hardware-exploration-chart-template.html")
            .bufferedReader()
            .use { it.readText() }
            .replace(
                oldValue = "'<%= apdexChartData =%>'",
                newValue = plotApdex(resultsPerInstanceType).toJson().toString()
            )
            .replace(
                oldValue = "'<%= errorRateChartData =%>'",
                newValue = plotErrorRate(resultsPerInstanceType).toJson().toString()
            )
            .replace(
                oldValue = "'<%= throughputChartData =%>'",
                newValue = plotThroughput(resultsPerInstanceType).toJson().toString()
            )
            .replace(
                oldValue = "<%= commit =%>",
                newValue = repo.getHead()
            )
            .replace(
                oldValue = "<%= application =%>",
                newValue = application
            )
        output.toFile().ensureParentDirectory().printWriter().use { it.print(report) }
        logger.info("Hardware exploration chart available at ${output.toUri()}")
    }

    private fun plotApdex(
        resultsPerInstanceType: Map<InstanceType, List<HardwareTestResult>>
    ): Chart<NodeCount> = resultsPerInstanceType
        .map { (instanceType, testResults) ->
            chartLine(
                data = testResults.map {
                    HardwarePoint(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        value = BigDecimal.valueOf(it.apdex).setScale(3, HALF_UP)
                    )
                },
                errorBars = testResults.map {
                    val spreadDiff = BigDecimal.valueOf(it.apdexSpread / 2).setScale(3, HALF_UP)
                    HardwareErrorBar(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        plus = spreadDiff,
                        minus = spreadDiff
                    )
                },
                label = instanceType.toString()
            )
        }
        .let { Chart(it) }

    private fun chartLine(
        data: List<Point<NodeCount>>,
        errorBars: List<ErrorBar>,
        label: String
    ): ChartLine<NodeCount> {
        val labelColor = presetLabelColor
        return ChartLine(
            data = data,
            errorBars = errorBars,
            label = label,
            type = "line",
            hidden = false,
            yAxisId = "y-axis-0",
            labelColor = labelColor
        )
    }

    private fun plotErrorRate(
        resultsPerInstanceType: Map<InstanceType, List<HardwareTestResult>>
    ): Chart<NodeCount> = resultsPerInstanceType
        .map { (instanceType, testResults) ->
            chartLine(
                data = testResults.map {
                    HardwarePoint(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        value = BigDecimal.valueOf(it.errorRate * 100).setScale(2, HALF_UP)
                    )
                },
                errorBars = testResults.map {
                    val spreadDiff = BigDecimal.valueOf((it.errorRateSpread / 2) * 100).setScale(2, HALF_UP)
                    HardwareErrorBar(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        plus = spreadDiff,
                        minus = spreadDiff
                    )
                },
                label = instanceType.toString()
            )
        }
        .let { Chart(it) }

    private fun plotThroughput(
        resultsPerInstanceType: Map<InstanceType, List<HardwareTestResult>>
    ): Chart<NodeCount> = resultsPerInstanceType
        .map { (instanceType, testResults) ->
            chartLine(
                data = testResults.map {
                    HardwarePoint(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        value = BigDecimal.valueOf(it.httpThroughput.count).setScale(0, HALF_UP)
                    )
                },
                errorBars = testResults.map {
                    val spreadDiff = BigDecimal.valueOf(it.httpThroughputSpread.count / 2).setScale(2, HALF_UP)
                    HardwareErrorBar(
                        nodeCount = NodeCount(it.hardware.nodeCount),
                        plus = spreadDiff,
                        minus = spreadDiff
                    )
                },
                label = instanceType.toString()
            )
        }
        .let { Chart(it) }
}

private class HardwarePoint(
    private val nodeCount: NodeCount,
    value: BigDecimal
) : Point<NodeCount> {
    override val x: NodeCount = nodeCount
    override val y: BigDecimal = value
    override fun labelX(): String = nodeCount.toString()
}

private class HardwareErrorBar(
    private val nodeCount: NodeCount,
    override val plus: BigDecimal,
    override val minus: BigDecimal
) : ErrorBar {
    override fun labelX(): String = nodeCount.toString()
}

private class NodeCount(
    val nodeCount: Int
) : Comparable<NodeCount> {
    override fun compareTo(other: NodeCount): Int = compareBy<NodeCount> { it.nodeCount }.compare(this, other)
    override fun toString(): String = nodeCount.toString()
}