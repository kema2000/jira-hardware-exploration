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
import com.atlassian.performance.tools.infrastructure.api.distribution.PublicJiraSoftwareDistribution
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
    private val aws = IntegrationTestRuntime.aws
    private val rootWorkspace = RootWorkspace()
    private val testWorkspace = rootWorkspace.currentTask.isolateTest("JiraInstanceTest")

    @Test
    fun shouldProvisionAnInstance() {
        provisionDcInstance()
    }

    fun provisionServerInstance() {
        val jiraVersion = "7.13.0"

        val dataset = HardwareExplorationIT().sevenMillionIssues
        val lifespan = Duration.ofHours(8)
        val infrastructure = InfrastructureFormula(
            investment = Investment(
                useCase = "Provision an ad hoc Jira Software environment",
                lifespan = lifespan
            ),
            jiraFormula = StandaloneFormula.Builder(
                database = dataset.database,
                jiraHomeSource = dataset.jiraHomeSource,
                productDistribution = PublicJiraSoftwareDistribution(jiraVersion))
                .computer(EbsEc2Instance(InstanceType.M44xlarge).withVolumeSize(300))
                .databaseComputer(EbsEc2Instance(InstanceType.M44xlarge).withVolumeSize(300))
                .adminPwd("MasterPassword18")
                .build(),
            virtualUsersFormula = AbsentVirtualUsersFormula(),
            aws = aws
        ).provision(testWorkspace.directory).infrastructure
        CustomDatasetSourceRegistry(rootWorkspace).register(infrastructure)
    }

    fun provisionDcInstance() {
        val jiraVersion = "7.13.0"

        val dataset = HardwareExplorationIT().sevenMillionIssues
        val lifespan = Duration.ofHours(8)
        val infrastructure = InfrastructureFormula(
            investment = Investment(
                useCase = "Provision an ad hoc Jira Software environment",
                lifespan = lifespan
            ),
            jiraFormula = DataCenterFormula.Builder(
                database = dataset.database,
                jiraHomeSource = dataset.jiraHomeSource,
                productDistribution = PublicJiraSoftwareDistribution(jiraVersion))
                .computer(EbsEc2Instance(InstanceType.M44xlarge).withVolumeSize(300))
                .databaseComputer(EbsEc2Instance(InstanceType.M44xlarge).withVolumeSize(300))
                .adminPwd("MasterPassword18")
                .build(),
            virtualUsersFormula = AbsentVirtualUsersFormula(),
            aws = aws
        ).provision(testWorkspace.directory).infrastructure
        CustomDatasetSourceRegistry(rootWorkspace).register(infrastructure)
    }

}
