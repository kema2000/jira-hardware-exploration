package com.atlassian.performance.tools.hardware

import com.atlassian.performance.tools.hardware.vu.CustomSetup
import com.atlassian.performance.tools.jiraactions.api.EDIT_ISSUE
import com.atlassian.performance.tools.jiraactions.api.EDIT_ISSUE_SUBMIT
import com.atlassian.performance.tools.jiraactions.api.SeededRandom
import com.atlassian.performance.tools.jiraactions.api.WebJira
import com.atlassian.performance.tools.jiraactions.api.action.*
import com.atlassian.performance.tools.jiraactions.api.measure.ActionMeter
import com.atlassian.performance.tools.jiraactions.api.memories.IssueMemory
import com.atlassian.performance.tools.jiraactions.api.memories.JqlMemory
import com.atlassian.performance.tools.jiraactions.api.memories.User
import com.atlassian.performance.tools.jiraactions.api.memories.adaptive.*
import com.atlassian.performance.tools.jiraactions.api.memories.adaptive.jql.JqlPrescription
import com.atlassian.performance.tools.jiraactions.api.memories.adaptive.jql.JqlPrescriptions
import com.atlassian.performance.tools.jiraactions.api.observation.IssueObservation
import com.atlassian.performance.tools.jiraactions.api.page.EditIssuePage
import com.atlassian.performance.tools.jiraactions.api.page.IssuePage
import com.atlassian.performance.tools.jiraactions.api.page.wait
import com.atlassian.performance.tools.jiraactions.page.form.*
import com.atlassian.performance.tools.jiraperformancetests.api.VisitableJira
import com.atlassian.performance.tools.jiraperformancetests.api.VisitableJiraRegistry
import com.atlassian.performance.tools.jirasoftwareactions.api.WebJiraSoftware
import com.atlassian.performance.tools.jirasoftwareactions.api.actions.BrowseBoardsAction
import com.atlassian.performance.tools.jirasoftwareactions.api.memories.AdaptiveBoardIdMemory
import com.atlassian.performance.tools.virtualusers.api.browsers.CloseableRemoteWebDriver
import com.atlassian.performance.tools.virtualusers.api.browsers.GoogleChrome
import com.atlassian.performance.tools.virtualusers.api.browsers.HeadlessChromeBrowser
import com.atlassian.performance.tools.virtualusers.api.diagnostics.WebDriverDiagnostics
import com.atlassian.performance.tools.workspace.api.RootWorkspace
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.Test
import org.junit.experimental.categories.Category
import org.openqa.selenium.By
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.ExpectedCondition
import org.openqa.selenium.support.ui.ExpectedConditions
import java.lang.RuntimeException
import java.net.URI
import java.time.Duration
import java.util.*
import org.openqa.selenium.JavascriptExecutor



/**
 * A quick single-action integration test, aimed at quick devloop while developing VU actions or page objects.
 */
class VirtualUserRteActionsTest {

    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val random = SeededRandom()
    private val root = RootWorkspace()
    private val registry = VisitableJiraRegistry(root)
    private val userMemory = AdaptiveUserMemory(random)
    private val projectMemory = AdaptiveProjectMemory(random)
    private val boardIdMemory = AdaptiveBoardIdMemory(random)
    private val jqlMemory = AdaptiveJqlMemoryNonEpic(random)
    private val issueKeyMemory = AdaptiveIssueKeyMemory(random)
    private val issueMemory = AdaptiveIssueMemory(issueKeyMemory, random)

    @Category(VirtualUserRteActionsTest::class)
    @Test
    fun shouldRunVirtualUserActions() {

        val jira = VisitableJira(URI.create("http://jpt-38d38-LoadBala-18BIW8BIG87QY-1334370059.eu-west-1.elb.amazonaws.com/"))
        registry.memorize(jira)


        (tryStartingChrome() ?: return).use { chrome ->
            val diagnostics = WebDriverDiagnostics(chrome.getDriver())
            try {
                val jira : VisitableJira? = registry.recall()
                if(jira == null){
                    throw RuntimeException("No JIRA available")
                }

                logger.info("Using ${jira.address} to test the virtual user...")
                val webJira = WebJira(
                    driver = chrome.getDriver(),
                    base = jira.address,
                    adminPassword = "MasterPassword18"

                )
                val actionMeter = ActionMeter(
                    virtualUser = UUID.randomUUID()
                )
                userMemory.remember(listOf(User("admin", "MasterPassword18")))

                LogInAction(webJira, actionMeter, userMemory).run()
                CustomSetup(jira = webJira, meter = actionMeter).run()


//                LogInAction(webJira, actionMeter, userMemory).run()
//                SetUpAction(webJira, actionMeter).run()
//                testMyAction(webJira, actionMeter)
//                exploreJiraData(webJira, actionMeter)
//                BrowseProjectsAction(webJira, actionMeter, projectMemory).run()
//                testInsert(webJira, actionMeter)
//                testVIewIssue(webJira, actionMeter)
//                testEdit(webJira, actionMeter)

                //test when rte disabled
//                SetUpAction(webJira, actionMeter).run()
//                exploreJiraData(webJira, actionMeter)
//                testInsert(webJira, actionMeter)
//                testVIewIssue(webJira, actionMeter)
//                testEdit(webJira, actionMeter)

            } catch (e: Exception) {
                diagnostics.diagnose(e)
                throw e
            }
        }
    }


    private fun tryStartingChrome(): CloseableRemoteWebDriver? {
        return try {
            GoogleChrome().start()
//            HeadlessChromeBrowser().start()
        } catch (e: Exception) {
            val ticket = URI("https://bulldog.internal.atlassian.com/browse/JPT-290")
            logger.info("Failed to start Google Chrome, skipping the VU test. If it impacts you, tell us in $ticket", e)
            null
        }
    }

    /**
     * Explores Jira to gather some data for memory-reliant actions.
     */
    private fun exploreJiraData(
        webJira: WebJira,
        actionMeter: ActionMeter
    ) {
        BrowseProjectsAction(webJira, actionMeter, projectMemory).run()
        val webJiraSoftware = WebJiraSoftware(webJira)
        BrowseBoardsAction(webJiraSoftware, actionMeter, boardIdMemory).run()
        SearchJqlAction(webJira, actionMeter, jqlMemory, issueKeyMemory).run()
    }

    /**
     * Tests your specific VU action. Feel free to adapt it to your needs.
     */
    private fun testMyAction(
        webJira: WebJira,
        actionMeter: ActionMeter
    ) {
        repeat(12) {
            val myAction = ViewIssueAction(
                jira = webJira,
                meter = actionMeter,
                issueKeyMemory = issueKeyMemory,
                issueMemory = issueMemory,
                jqlMemory = jqlMemory
            )
            myAction.run()
        }
    }

    private fun testInsert(webJira: WebJira, actionMeter: ActionMeter) {
        repeat(1) {
                val insertAction = CreateIssueAction(
                jira = webJira,
                meter = actionMeter,
                seededRandom = random,
                projectMemory = projectMemory
            )
            insertAction.run()
        }
    }

    private fun testVIewIssue(webJira: WebJira, actionMeter: ActionMeter) {
            SearchJqlAction(
                jira = webJira,
                meter = actionMeter,
                jqlMemory = jqlMemory,
                issueKeyMemory = issueKeyMemory
            ).run()

            ViewIssueAction(
                jira = webJira,
                meter = actionMeter,
                issueKeyMemory = issueKeyMemory,
                issueMemory = issueMemory,
                jqlMemory = jqlMemory
            ).run()

            logger.info("Issue key remmbered : ${issueKeyMemory.recall()}")
            logger.info("Issue  remmbered : ${issueMemory.recall()}")
    }

    private fun testEdit(webJira: WebJira, actionMeter: ActionMeter) {
            logger.info("Issue  remmbered : ${issueMemory.recall()}")

            val action  = EditIssueAction(
                jira = webJira,
                meter = actionMeter,
                issueMemory = issueMemory
            )
            action.run()

            AddCommentAction(
                jira = webJira,
                meter = actionMeter,
                issueMemory = issueMemory
            ).run()
    }


    class AdaptiveJqlMemoryNonEpic(
        private val random: SeededRandom
    ) : JqlMemory {

        private val logger: Logger = LogManager.getLogger(this::class.java)

        private val jqls = mutableListOf(
            "issuetype in (Bug, Task) AND resolved is not empty order by description",
            "issuetype in (Bug, Task) AND text ~ \"a*\" order by summary"
        )
        private val jqlPrescriptions = mutableSetOf(
            JqlPrescriptions.prioritiesInEnumeratedList(random),
            JqlPrescriptions.specifiedProject,
            JqlPrescriptions.specifiedAssignee,
            JqlPrescriptions.previousReporters,
            JqlPrescriptions.specifiedAssigneeInSpecifiedProject,
            JqlPrescriptions.filteredByGivenWord(random)
        )

        override fun observe(issuePage: IssuePage) {
            val bakedJql = jqlPrescriptions.asSequence()
                .map { BakedJql(it, it(issuePage)) }
                .filter { it.jql != null }
                .firstOrNull()

            bakedJql?.let {
                logger.debug("Rendered a new jql query: <<${it.jql!!}>>")
                jqls.add(it.jql)
                jqlPrescriptions.remove(it.jqlPrescription)
            }
        }

        override fun recall(): String? {
            return random.pick(jqls)
        }

        override fun remember(memories: Collection<String>) {
            jqls.addAll(memories)
        }
    }

    data class BakedJql(
        val jqlPrescription: JqlPrescription,
        val jql: String?
    )

}

fun EditIssueAction.run2(jira: WebJira, meter: ActionMeter, issueMemory: IssueMemory){
    val issue = issueMemory.recall { it.editable && it.type != "Epic" }
    if (issue == null) {
        //logger.debug("Cannot edit any issue, because I didn't see any editable issues")
        return
    }
    meter.measure(EDIT_ISSUE) {
        val editIssueForm = jira
            .goToEditIssue(issue.id)
            .waitForEditIssueForm()
            .fillForm2(jira.driver)
        meter.measure(
            key = EDIT_ISSUE_SUBMIT,
            action = { editIssueForm.submit() },
            observation = { IssueObservation(issue.key).serialize() }
        )
        return@measure editIssueForm
    }
}



fun EditIssuePage.fillForm2(jira: WebDriver): EditIssuePage {
    val summaryLocator2 = By.id("summary")
    summaryLocator2.clearAndTypeIfPresent2(jira, "summary")


    initRteEditor(jira)

    val descriptionLocator = By.id("description")
    descriptionLocator.clearAndTypeIfPresent2(jira, "description")

    return this
}

private fun initRteEditor(jira: WebDriver) {

    /**
     *
    //try remove some css
    val cssDisableRte = "'textarea long-field wiki-textfield long-field mentionable wiki-editor-initialised'"
    val cssEnableRte = "'textarea long-field wiki-textfield long-field mentionable wiki-editor-initialised wiki-edit-wrapped richeditor-cover'"
    val cssEnableRte1 = "'textarea long-field wiki-textfield long-field mentionable wiki-editor-initialised wiki-edit-wrapped'"
    val cssEnableRte2 = "'textarea long-field wiki-textfield long-field mentionable wiki-editor-initialised richeditor-cover'"

    val js = jira as JavascriptExecutor
    js.executeScript("document.getElementById('description').setAttribute('class'," + cssEnableRte1 + ")");
     */

    ////li[@data-mode="source"]/a[text()='Text']
    val textXpath = "//li[@data-mode='source']/a[text()='Text']"
    val elements = jira.findElements(By.xpath(textXpath))
    println("element found is ${elements.size}")
    for (webElement in elements) {
        //jira.wait(timeout = Duration.ofSeconds(20), condition = ExpectedConditions.elementToBeClickable(webElement))
        webElement.click()
    }

}

fun By.clearAndTypeIfPresent2(driver: WebDriver, text: String) {
    if (driver.isElementPresent(this)) {
        val webElement = driver.findElement(this)

        driver.wait(timeout = Duration.ofSeconds(20), condition = ExpectedConditions.elementToBeClickable(webElement))
        webElement.clear()
        webElement.sendKeys(text)
        println("Element details: enabled : ${webElement.isEnabled}, size : ${webElement.size.height} X ${webElement.size.width}")
    }
}

fun elementToBeClickable(element: WebElement): ExpectedCondition<WebElement> {
    return object : ExpectedCondition<WebElement> {

        override fun apply(driver: WebDriver?): WebElement? {
            val visibleElement = visibilityOf(element).apply(driver)
            try {
                return if (visibleElement != null && visibleElement!!.isEnabled()) {
                    visibleElement
                } else null
            } catch (e: StaleElementReferenceException) {
                return null
            }

        }

        override fun toString(): String {
            return "element to be clickable: $element"
        }
    }
}

fun visibilityOf(element: WebElement): ExpectedCondition<WebElement> {
    return object : ExpectedCondition<WebElement> {
        override fun apply(driver: WebDriver?): WebElement? {
            return if (element.isDisplayed || (element.size.height>0 && element.size.width>0)) element else null;
        }

        override fun toString(): String {
            return "visibility of $element"
        }
    }
}

fun WebDriver.isElementPresent(
    locator: By
): Boolean {
    return this.findElements(locator).isNotEmpty()
}




