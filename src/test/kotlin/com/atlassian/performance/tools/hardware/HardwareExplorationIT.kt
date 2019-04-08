package com.atlassian.performance.tools.hardware

import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.model.InstanceType.*
import com.amazonaws.services.s3.transfer.TransferManagerBuilder
import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.awsinfrastructure.S3DatasetPackage
import com.atlassian.performance.tools.hardware.IntegrationTestRuntime.aws
import com.atlassian.performance.tools.hardware.IntegrationTestRuntime.logContext
import com.atlassian.performance.tools.hardware.IntegrationTestRuntime.taskName
import com.atlassian.performance.tools.hardware.IntegrationTestRuntime.workspace
import com.atlassian.performance.tools.hardware.failure.BugAwareTolerance
import com.atlassian.performance.tools.hardware.guidance.DbExplorationGuidance
import com.atlassian.performance.tools.hardware.guidance.ExplorationGuidance
import com.atlassian.performance.tools.hardware.guidance.JiraExplorationGuidance
import com.atlassian.performance.tools.infrastructure.api.database.PostgresDatabase
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomePackage
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer.time
import com.atlassian.performance.tools.lib.LicenseOverridingDatabase
import com.atlassian.performance.tools.lib.overrideDatabase
import com.atlassian.performance.tools.lib.s3cache.S3Cache
import com.atlassian.performance.tools.lib.toExistingFile
import com.atlassian.performance.tools.lib.workspace.GitRepo2
import com.atlassian.performance.tools.virtualusers.api.TemporalRate
import com.atlassian.performance.tools.virtualusers.api.VirtualUserLoad
import org.apache.logging.log4j.Logger
import org.eclipse.jgit.api.Git
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.time.Duration

const val jiraAdminPassword = "MasterPassword18"

class HardwareExplorationIT {

    private val logger: Logger = IntegrationTestRuntime.logContext.getLogger(this::class.java.canonicalName)

    internal val sevenMillionIssues = StorageLocation(
        uri = URI("s3://jpt-custom-postgres-xl/dataset-7m"),
        region = Regions.EU_WEST_1
    ).let { location ->
        Dataset(
            label = "7M issues",
            database = PostgresDatabase(
                source = S3DatasetPackage(
                    artifactName = "database.tar.bz2",
                    location = location,
                    unpackedPath = "database",
                    downloadTimeout = Duration.ofMinutes(40)
                ),
                dbName = "atldb",
                dbUser = "postgres",
                dbPassword = "postgres"
            ),
            jiraHomeSource = JiraHomePackage(
                S3DatasetPackage(
                    artifactName = "jirahome.tar.bz2",
                    location = location,
                    unpackedPath = "jirahome",
                    downloadTimeout = Duration.ofMinutes(40)
                )
            )
        )
    }.overrideDatabase { originalDataset ->
        val localLicense = Paths.get("jira-license.txt")
        LicenseOverridingDatabase(
            originalDataset.database,
            listOf(
                localLicense
                    .toExistingFile()
                    ?.readText()
                    ?: throw  Exception("Put a Jira license to ${localLicense.toAbsolutePath()}")
            ))
    }

    val instanceTypeList: List<String> = System.getProperty("instanceTypes", "c5.2xlarge,c5.4xlarge,c4.8xlarge,c5.18xlarge").split(",")
    val fixedNodeNum: Int = Integer.parseInt(System.getProperty("instanceNumbers", "0"))

    private val jiraInstanceTypes = instanceTypeList.map { instanceTypeStr ->
        fromValue(instanceTypeStr)
    }

    private val resultCache = HardwareExplorationResultCache(workspace.directory.resolve("result-cache.json"))

    @Test
    fun shouldExploreHardware() {
        requireCleanRepo()
        val cache = getWorkspaceCache()
        logger.info("Using $cache")
        time("download") { cache.download() }
        val jiraExploration = try {
            exploreJiraHardware()
        } finally {
            time("upload") { cache.upload() }
        }
//        val jiraRecommendations = recommendJiraHardware(jiraExploration)
//        try {
//            exploreDbHardware(jiraRecommendations, jiraExploration)
//        } finally {
//            time("upload") { cache.upload() }
//        }
    }

    private fun requireCleanRepo() {
        val status = Git(GitRepo2.findInAncestors(File(".").absoluteFile)).status().call()
        if (status.isClean.not()) {
            throw Exception("Your Git repo is not clean. Please commit the changes and consider pushing them.")
        }
    }

    private fun getWorkspaceCache(): S3Cache = S3Cache(
        transfer = TransferManagerBuilder.standard()
            .withS3Client(aws.s3)
            .build(),
        bucketName = "quicksilver-jhwr-cache-ireland",
        cacheKey = taskName,
        localPath = workspace.directory
    )

    private fun recommendJiraHardware(
        jiraExploration: List<HardwareExplorationResult>
    ): List<HardwareTestResult> {
        val recommendations = jiraExploration
            .mapNotNull { it.testResult }
            .filter { it.apdex > 0.70 }
            .sortedByDescending { it.apdex }
            .take(2)
        logger.info("Recommending $recommendations")
        return recommendations
    }

    private fun exploreJiraHardware(): List<HardwareExplorationResult> = explore(
        when (fixedNodeNum) {
            0 -> JiraExplorationGuidance(
                instanceTypes = jiraInstanceTypes,
                minNodeCount = 1,
                maxNodeCount = 16,
                minNodeCountForAvailability = 3,
                minApdexGain = 0.01,
                db = M44xlarge,
                resultsCache = resultCache
            )
            else -> JiraExplorationGuidance(
                instanceTypes = jiraInstanceTypes,
                minNodeCount = fixedNodeNum,
                maxNodeCount = fixedNodeNum,
                minNodeCountForAvailability = 3,
                minApdexGain = 0.01,
                db = M44xlarge,
                resultsCache = resultCache
            )
        }
    )

    private fun explore(
        guidance: ExplorationGuidance
    ): List<HardwareExplorationResult> = HardwareExploration(
        scale = ApplicationScale(
            description = "Jira L profile",
            dataset = sevenMillionIssues,
            load = VirtualUserLoad.Builder()
                .virtualUsers(150)
                .ramp(Duration.ofSeconds(90))
                .flat(Duration.ofMinutes(20))
                .maxOverallLoad(TemporalRate(30.0, Duration.ofSeconds(1)))
                .build(),
            vuNodes = 12
        ),
        guidance = guidance,
        maxApdexSpread = 0.10,
        maxErrorRate = 0.05,
        pastFailures = BugAwareTolerance(logger),
        repeats = 2,
        investment = Investment(
            useCase = "Test hardware recommendations - $taskName",
            lifespan = Duration.ofHours(2)
        ),
        aws = aws,
        task = workspace
    ).exploreHardware()

    private fun exploreDbHardware(
        jiraRecommendations: List<HardwareTestResult>,
        jiraExploration: List<HardwareExplorationResult>
    ): List<HardwareExplorationResult> = explore(
        DbExplorationGuidance(
            dbs = listOf(
                M4Large,
                M4Xlarge,
                M42xlarge,
                M44xlarge
            ),
            jiraRecommendations = jiraRecommendations,
            jiraExploration = jiraExploration,
            jiraOrder = jiraInstanceTypes,
            resultsCache = resultCache
        )
    )
}
