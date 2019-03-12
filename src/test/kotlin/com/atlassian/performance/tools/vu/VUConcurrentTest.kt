package com.atlassian.performance.tools.vu

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.model.InstanceType
import com.atlassian.performance.tools.aws.api.Aws
import com.atlassian.performance.tools.aws.api.Investment
import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.aws.api.TextCapacityMediator
import com.atlassian.performance.tools.awsinfrastructure.api.DatasetCatalogue
import com.atlassian.performance.tools.awsinfrastructure.api.InfrastructureFormula
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C4EightExtraLargeElastic
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.EbsEc2Instance
import com.atlassian.performance.tools.awsinfrastructure.api.jira.DataCenterFormula
import com.atlassian.performance.tools.awsinfrastructure.api.jira.JiraFormula
import com.atlassian.performance.tools.awsinfrastructure.api.jira.StandaloneFormula
import com.atlassian.performance.tools.awsinfrastructure.api.loadbalancer.ElasticLoadBalancerFormula
import com.atlassian.performance.tools.awsinfrastructure.api.storage.JiraSoftwareStorage
import com.atlassian.performance.tools.awsinfrastructure.api.virtualusers.MulticastVirtualUsersFormula
import com.atlassian.performance.tools.hardware.ApplicationScale
import com.atlassian.performance.tools.hardware.vu.VuUserCreationScenario
import com.atlassian.performance.tools.infrastructure.api.app.Apps
import com.atlassian.performance.tools.infrastructure.api.browser.chromium.Chromium69
import com.atlassian.performance.tools.infrastructure.api.jira.JiraLaunchTimeouts
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.infrastructure.api.profiler.AsyncProfiler
import com.atlassian.performance.tools.infrastructure.api.splunk.DisabledSplunkForwarder
import com.atlassian.performance.tools.io.api.dereference
import com.atlassian.performance.tools.jiraperformancetests.api.GroupableTest
import com.atlassian.performance.tools.jiraperformancetests.api.ProvisioningPerformanceTest
import com.atlassian.performance.tools.lib.LicenseOverridingDatabase
import com.atlassian.performance.tools.lib.infrastructure.ThrottlingMulticastVirtualUsersFormula
import com.atlassian.performance.tools.lib.overrideDatabase
import com.atlassian.performance.tools.lib.toExistingFile
import com.atlassian.performance.tools.virtualusers.api.TemporalRate
import com.atlassian.performance.tools.virtualusers.api.VirtualUserLoad
import com.atlassian.performance.tools.virtualusers.api.browsers.HeadlessChromeBrowser
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserBehavior
import com.atlassian.performance.tools.workspace.api.RootWorkspace
import com.atlassian.performance.tools.workspace.api.TestWorkspace
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.junit.Test
import java.net.URI
import java.nio.file.Paths
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors

class VUConcurrentTest{

    val oneMillionIssues = DatasetCatalogue().custom(
        location = StorageLocation(
            uri = URI("s3://jpt-custom-datasets-storage-a008820-datasetbucket-1sjxdtrv5hdhj/")
                .resolve("a12fc4c5-3973-41f0-bf56-ede393677028"),
            region = Regions.EU_WEST_1
        ),
        label = "1M issues",
        databaseDownload = Duration.ofMinutes(20),
        jiraHomeDownload = Duration.ofMinutes(20)
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

    private val scale = ApplicationScale(
        description = "Jira L profile",
        dataset = oneMillionIssues,
        load = VirtualUserLoad.Builder()
            .virtualUsers(75)
            .ramp(Duration.ofSeconds(90))
            .flat(Duration.ofMinutes(20))
            .maxOverallLoad(TemporalRate(15.0, Duration.ofSeconds(1)))
            .build(),
        vuNodes = 6
    )

    private val virtualUsers: VirtualUserBehavior = VirtualUserBehavior.Builder(VuUserCreationScenario::class.java)
        .load(scale.load)
        .seed(78432)
        .diagnosticsLimit(32)
        .browser(HeadlessChromeBrowser::class.java)
        .createUsers(true)
        .build()

    private val root: RootWorkspace = RootWorkspace()


    private fun createPtestInstance(
        cohort: String, jiraFormula: JiraFormula
    ): ProvisioningPerformanceTest = ProvisioningPerformanceTest(
        cohort = cohort,
        infrastructureFormula = InfrastructureFormula(
            investment = Investment(
                useCase = "Test VU concurrent testing",
                lifespan = Duration.ofHours(2)),
            jiraFormula = jiraFormula,
            virtualUsersFormula = ThrottlingMulticastVirtualUsersFormula(
                MulticastVirtualUsersFormula(
                    nodes = scale.vuNodes,
                    shadowJar = dereference("jpt.virtual-users.shadow-jar"),
                    splunkForwarder = DisabledSplunkForwarder(),
                    browser = Chromium69()
                )
            ),
            aws = Aws(
                credentialsProvider = AWSCredentialsProviderChain(
                    STSAssumeRoleSessionCredentialsProvider.Builder(
                        "arn:aws:iam::695067801333:role/bamboo-sydney",
                        UUID.randomUUID().toString()).build(),
                    DefaultAWSCredentialsProviderChain()
                ),
                region = Regions.EU_WEST_1,
                regionsWithHousekeeping = listOf(Regions.EU_WEST_1),
                capacity = TextCapacityMediator(Regions.EU_WEST_1),
                batchingCloudformationRefreshPeriod = Duration.ofSeconds(20)
            )
        )
    )

    @Test
    fun shouldHaveNoErrors() {
        val executor = Executors.newCachedThreadPool(
            ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("VU-concurrent-test-thread-%d")
                .build()
        )
        listOf(
            DatacenterVUConcurrentTest()
        )
            .map { test ->
                executor.submit {
                    test.run(
                        group = "VU test",
                        workspace = root.currentTask
                    )
                }
            }
            .forEach { it.get() }
    }

    private inner class DatacenterVUConcurrentTest: GroupableTest("DataCenter") {
        override fun run(workspace: TestWorkspace) {
            val executor = Executors.newCachedThreadPool(
                ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("VU-concurrent-test-thread-%d")
                    .build()
            )
            val dcTest = createPtestInstance(
                cohort = "VUConcurrent",
                jiraFormula = DataCenterFormula(
                    apps = Apps(emptyList()),
                    application = JiraSoftwareStorage("7.13.0"),
                    jiraHomeSource = scale.dataset.jiraHomeSource,
                    database = scale.dataset.database,
                    configs = listOf(
                        JiraNodeConfig.Builder()
                            .name("jira-node-0")
                            .profiler(AsyncProfiler())
                            .launchTimeouts(
                                JiraLaunchTimeouts.Builder()
                                    .initTimeout(Duration.ofMinutes(7))
                                    .build()
                            )
                            .build())
                    ,
                    loadBalancerFormula = ElasticLoadBalancerFormula(),
                    computer = EbsEc2Instance(InstanceType.C52xlarge)
                )
            )

            val futurResults = dcTest.runAsync(root.currentTask.isolateTest("001"), executor, virtualUsers)
            val results = futurResults.get()
            println(results.toString())
            executor.shutdownNow()
        }
    }

    private inner class StandaloneVUConcurrentTest: GroupableTest("Standalone") {
        override fun run(workspace: TestWorkspace) {
            val executor = Executors.newCachedThreadPool(
                ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("VU-concurrent-test-thread-%d")
                    .build()
            )
            val dcTest = createPtestInstance(
                cohort = "VUConcurrent",
                jiraFormula = StandaloneFormula(
                    apps = Apps(emptyList()),
                    application = JiraSoftwareStorage("7.13.0"),
                    jiraHomeSource = scale.dataset.jiraHomeSource,
                    database = scale.dataset.database,
                    config = JiraNodeConfig.Builder()
                        .profiler(AsyncProfiler())
                        .build(),
                    computer = C4EightExtraLargeElastic()
                ))

            val futurResults = dcTest.runAsync(root.currentTask.isolateTest("001"), executor, virtualUsers)
            val results = futurResults.get()
            println(results.toString())
            executor.shutdownNow()
        }
    }

}

