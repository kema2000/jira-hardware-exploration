package com.atlassian.performance.tools.hardware

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.ec2.model.InstanceType.*
import com.atlassian.performance.tools.aws.api.Aws
import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.aws.api.TextCapacityMediator
import com.atlassian.performance.tools.awsinfrastructure.S3DatasetPackage
import com.atlassian.performance.tools.awsinfrastructure.api.CustomDatasetSource
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.infrastructure.api.database.DbType
import com.atlassian.performance.tools.infrastructure.api.database.PostgresDatabase
import com.atlassian.performance.tools.infrastructure.api.dataset.Dataset
import com.atlassian.performance.tools.infrastructure.api.dataset.DatasetPackage
import com.atlassian.performance.tools.infrastructure.api.dataset.FileArchiver
import com.atlassian.performance.tools.infrastructure.api.dataset.HttpDatasetPackage
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomePackage
import com.atlassian.performance.tools.lib.LicenseOverridingDatabase
import com.atlassian.performance.tools.lib.LogConfigurationFactory
import com.atlassian.performance.tools.lib.overrideDatabase
import com.atlassian.performance.tools.lib.toExistingFile
import com.atlassian.performance.tools.virtualusers.api.TemporalRate
import com.atlassian.performance.tools.virtualusers.api.VirtualUserLoad
import com.atlassian.performance.tools.workspace.api.RootWorkspace
import com.atlassian.performance.tools.workspace.api.TestWorkspace
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.ConfigurationFactory
import org.junit.BeforeClass
import org.junit.Test
import java.net.URI
import java.nio.file.Paths
import java.time.Duration

const val jiraAdminPassword = "MasterPassword18"

class HardwareExplorationIT {

    private val logger: Logger = IntegrationTestRuntime.logContext.getLogger(this::class.java.canonicalName)
    private val oneMillionIssues = DatasetCatalogue().custom(
        location = StorageLocation(
            uri = URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/")
                .resolve("a12fc4c5-3973-41f0-bf56-ede393677028"),
            region = EU_WEST_1
        ),
        label = "1M issues",
        databaseDownload = Duration.ofMinutes(20),
        jiraHomeDownload = Duration.ofMinutes(20),
        dbType = DbType.Postgres
    ).overrideDatabase { originalDataset ->
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

    val location = StorageLocation(
        //s3://jpt-custom-postgres-xl/dataset-7m/jirahome.tar.bz2
        uri = URI("s3://jpt-custom-postgres-xl/")
            .resolve("dataset-7m"),
        region = Regions.EU_WEST_1
    )

    val databse = PostgresDatabase(
        source = S3DatasetPackage(
            artifactName = "database.tar.bz2",
            location = location,
            unpackedPath = "database",
            downloadTimeout = Duration.ofMinutes(20)
        ),
        dbName = "atldb",
        dbUser = "postgres",
        dbPassword ="postgres"
    )

    val sevenMillionIssues = DatasetCatalogue().custom(
        location = location,
        label = "7M issues",
        jiraHomeDownload = Duration.ofMinutes(40),
        databse = databse
    ).overrideDatabase { originalDataset ->
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

    private val failureTolerance = object : FailureTolerance {
        val cleaning = CleaningFailureTolerance()
        val logging = LoggingFailureTolerance(logger)

        override fun handle(failure: Exception, workspace: TestWorkspace) {
            when {
                causedByJperf387(failure) -> cleanAfterKnownIssue(failure, workspace, "JPERF-387")
                causedByJperf382(failure) -> cleanAfterKnownIssue(failure, workspace, "JPERF-382")
                else -> logging.handle(failure, workspace)
            }
        }

        private fun causedByJperf382(
            failure: Exception
        ): Boolean = failure
            .message!!
            .contains("java.net.SocketTimeoutException: Read timed out")

        private fun causedByJperf387(
            failure: Exception
        ): Boolean = failure
            .message!!
            .contains("Failed to install")

        private fun cleanAfterKnownIssue(
            failure: Exception,
            workspace: TestWorkspace,
            issueKey: String
        ) {
            logger.warn("Failure in $workspace due to https://ecosystem.atlassian.net/browse/$issueKey, cleaning...")
            cleaning.handle(failure, workspace)
        }
    }

    @Test
    fun shouldExploreHardware() {
        HardwareExploration(
            scale = ApplicationScale(
                description = "Jira XL profile",
                dataset = sevenMillionIssues,
                load = VirtualUserLoad.Builder()
                    .virtualUsers(75)
                    .ramp(Duration.ofSeconds(90))
                    .flat(Duration.ofMinutes(20))
                    .maxOverallLoad(TemporalRate(15.0, Duration.ofSeconds(1)))
                    .build(),
                vuNodes = 6
            ),
            guidance = ExplorationGuidance(
                instanceTypes = listOf(
                    C52xlarge,
                    C54xlarge,
                    C48xlarge,
                    C518xlarge
                ),
                maxNodeCount = 16,
                minNodeCountForAvailability = 3,
                repeats = 2,
                minApdexGain = 0.01,
                maxApdexSpread = 0.10,
                maxErrorRate = 0.05,
                pastFailures = failureTolerance
            ),
            investment = Investment(
                useCase = "Test hardware recommendations - ${IntegrationTestRuntime.taskName}",
                lifespan = Duration.ofHours(2)
            ),
            aws = IntegrationTestRuntime.aws,
            task = IntegrationTestRuntime.workspace
        ).exploreHardware()
    }

}
