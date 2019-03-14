package com.atlassian.performance.tools.hardware

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.model.InstanceType
import com.atlassian.performance.tools.aws.api.*
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.awsinfrastructure.api.InfrastructureFormula
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C4EightExtraLargeElastic
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C5NineExtraLargeEphemeral
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.EbsEc2Instance
import com.atlassian.performance.tools.awsinfrastructure.api.jira.DataCenterFormula
import com.atlassian.performance.tools.awsinfrastructure.api.jira.StandaloneFormula
import com.atlassian.performance.tools.awsinfrastructure.api.loadbalancer.ElasticLoadBalancerFormula
import com.atlassian.performance.tools.awsinfrastructure.api.storage.JiraSoftwareStorage
import com.atlassian.performance.tools.awsinfrastructure.api.virtualusers.AbsentVirtualUsersFormula
import com.atlassian.performance.tools.infrastructure.api.app.Apps
import com.atlassian.performance.tools.infrastructure.api.database.DbType
import com.atlassian.performance.tools.infrastructure.api.jira.JiraLaunchTimeouts
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.infrastructure.api.jvm.EnabledJvmDebug
import com.atlassian.performance.tools.infrastructure.api.profiler.AsyncProfiler
import com.atlassian.performance.tools.jiraperformancetests.api.CustomDatasetSourceRegistry
import com.atlassian.performance.tools.lib.LicenseOverridingDatabase
import com.atlassian.performance.tools.lib.overrideDatabase
import com.atlassian.performance.tools.lib.toExistingFile
import com.atlassian.performance.tools.workspace.api.RootWorkspace
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.Test
import java.net.URI
import java.nio.file.Paths
import java.time.Duration

class JiraInstanceTest {
    private val aws = Aws(
        credentialsProvider = DefaultAWSCredentialsProviderChain(),
        region = Regions.EU_WEST_1,
        regionsWithHousekeeping = listOf(Regions.EU_WEST_1),
        capacity = TextCapacityMediator(Regions.EU_WEST_1),
        batchingCloudformationRefreshPeriod = Duration.ofSeconds(20)
    )
    private val rootWorkspace = RootWorkspace()
    private val testWorkspace = rootWorkspace.currentTask.isolateTest("JiraInstanceTest")

    private val logger: Logger = LogManager.getLogger(this::class.java)

    private val oneMillionIssues = DatasetCatalogue().custom(
        location = StorageLocation(
            uri = URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/")
                .resolve("a12fc4c5-3973-41f0-bf56-ede393677028"),
            region = Regions.EU_WEST_1
        ),
        label = "1M issues",
        databaseDownload = Duration.ofMinutes(20),
        jiraHomeDownload = Duration.ofMinutes(20),
        dbType = DbType.MySql
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

    private val sevenMillionIssues = DatasetCatalogue().custom(
        location = StorageLocation(
            //s3://jpt-custom-postgres-xl/dataset-7m/jirahome.tar.bz2
            uri = URI("s3://jpt-custom-postgres-xl/")
                .resolve("dataset-7m"),
            region = Regions.EU_WEST_1
        ),
        label = "6M issues",
        databaseDownload = Duration.ofMinutes(40),
        jiraHomeDownload = Duration.ofMinutes(40),
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

    @Test
    fun shouldProvisionAnInstance() {
        val jiraVersion = "7.13.0"

        val dataset = sevenMillionIssues
        val lifespan = Duration.ofHours(8)
        val infrastructure = InfrastructureFormula(
            investment = Investment(
                useCase = "Provision an ad hoc Jira Software environment",
                lifespan = lifespan
            ),
//            jiraFormula = DataCenterFormula(
//                apps = Apps(emptyList()),
//                application = JiraSoftwareStorage("7.13.0"),
//                jiraHomeSource = dataset.jiraHomeSource,
//                database = dataset.database,
//                configs = listOf(
//                    JiraNodeConfig.Builder()
//                        .name("jira-node-1")
//                        .build()
//                ),
//                loadBalancerFormula = ElasticLoadBalancerFormula(),
//                computer = EbsEc2Instance(InstanceType.C4Large)
//            ),
            jiraFormula = StandaloneFormula(
                apps = Apps(emptyList()),
                application = JiraSoftwareStorage(jiraVersion),
                jiraHomeSource = dataset.jiraHomeSource,
                database = dataset.database,
                config = JiraNodeConfig.Builder().build(),
                computer = C5NineExtraLargeEphemeral()
            ),
            virtualUsersFormula = AbsentVirtualUsersFormula(),
            aws = aws
        ).provision(testWorkspace.directory).infrastructure
        CustomDatasetSourceRegistry(rootWorkspace).register(infrastructure)

    }

}
