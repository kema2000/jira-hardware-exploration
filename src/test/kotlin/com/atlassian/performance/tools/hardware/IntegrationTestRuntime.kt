package com.atlassian.performance.tools.hardware

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider
import com.amazonaws.regions.Regions
import com.atlassian.performance.tools.aws.api.Aws
import com.atlassian.performance.tools.aws.api.Storage
import com.atlassian.performance.tools.aws.api.TextCapacityMediator
import com.atlassian.performance.tools.lib.LogConfigurationFactory
import com.atlassian.performance.tools.workspace.api.RootWorkspace
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.ConfigurationFactory
import org.apache.logging.log4j.spi.LoggerContext
import java.nio.file.Paths
import java.time.Duration
import java.util.*

object IntegrationTestRuntime {

    const val taskName = "QUICK-94-run-on-ci"
    val workspace = RootWorkspace(Paths.get("build")).isolateTask(taskName)

    val aws: Aws
    val logContext: LoggerContext

    init {
        ConfigurationFactory.setConfigurationFactory(LogConfigurationFactory(workspace))
        logContext = LogManager.getContext()
        var roleArn: String = System.getenv("atl_bamboo_assumrole") ?: "arn:aws:iam::695067801333:role/server-gdn-bamboo"
        aws = Aws(
            credentialsProvider = AWSCredentialsProviderChain(
                STSAssumeRoleSessionCredentialsProvider.Builder(
                    roleArn,
                    UUID.randomUUID().toString()).build(),
                DefaultAWSCredentialsProviderChain()
            ),
            region = Regions.EU_WEST_1,
            regionsWithHousekeeping = listOf(Regions.EU_WEST_1),
            capacity = TextCapacityMediator(Regions.EU_WEST_1),
            batchingCloudformationRefreshPeriod = Duration.ofSeconds(20)
        )
        initWorkSpace()
    }

    fun initWorkSpace(){
        //download the result
        try{
            if(System.getenv("bamboo_buildResultKey") != null){
                println("Downloading result from s3 -- STARTED")
                Storage(aws.s3, prefix="QUICK-94-workspace",
                    bucketName = "temp-quicksilver").download(workspace.directory)
                println("Downloading result from s3 -- FINISHED")
            }
        } catch(e: Exception){
            //do nothing
            println("Downloading result from s3 -- Failed : $e")
        }
    }

    fun uploadResult(){
        try {
            if(System.getenv("bamboo_buildResultKey") != null){
                println("Uploading result to s3 -- STARTED")
                Storage(aws.s3, prefix="QUICK-94-workspace",
                    bucketName = "temp-quicksilver").upload(workspace.directory.toFile())
                println("Uploading result to s3 -- Finished")
            }
        } catch(e: Exception){
            //do nothing
            println("Uploading result to s3 -- Failed : $e")
        }
    }

}