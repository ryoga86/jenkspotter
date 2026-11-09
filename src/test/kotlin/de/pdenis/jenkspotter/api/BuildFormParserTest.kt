package de.pdenis.jenkspotter.api

import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.ui.BranchJobs
import de.pdenis.jenkspotter.ui.JenkinsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildFormParserTest {
    // Captured from the Docker Jenkins (team/all-params) with Active Choices 2.x.
    private val html = javaClass.getResource("/fixtures/build-form-active-choices.html")!!.readText()

    @Test
    fun `active choices, checkboxes, cascades and credentials are read from the form`() {
        val form = BuildFormParser.parse(html)
        assertNotNull(form.crumb)
        val region = form.parameters.getValue("REGION")
        assertEquals(listOf("eu-central-1", "us-east-1", "ap-south-1"), region.values)
        assertEquals(setOf("us-east-1"), region.selected)
        assertFalse(region.multiple)
        val features = form.parameters.getValue("FEATURES")
        assertTrue(features.multiple)
        assertEquals(listOf("metrics", "tracing", "logging"), features.values)
        val zone = form.parameters.getValue("ZONE")
        assertEquals(listOf("REGION"), zone.cascade!!.referencedParameters)
        assertTrue(zone.cascade!!.proxyId.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(listOf("deploy-token"), form.parameters.getValue("TOKEN").values)
        // Plain parameters have no choices in the form and are left to the JSON definition.
        assertNull(form.parameters["NAME"])
    }

    @Test
    fun `cascade answer strips selected and disabled markers`() {
        val proxy = CascadeProxy("id", listOf("A"))
        val c = BuildFormParser.parseCascade(listOf("x", "y:selected", "z:disabled"), listOf("X", "Y:selected", "Z"), false, proxy)
        assertEquals(listOf("x", "y", "z"), c.values)
        assertEquals(listOf("X", "Y", "Z"), c.labels)
        assertEquals(setOf("y"), c.selected)
    }

    @Test
    fun `pasted classic and blue ocean urls resolve to jobs`() {
        val bases = listOf("https://ci.example.com/jenkins/", "https://ci.example.com/")
        assertEquals(JenkinsUi.PastedJob("https://ci.example.com/jenkins/", "team/app/feature%2Fx"),
            JenkinsUi.parse("https://ci.example.com/jenkins/job/team/job/app/job/feature%252Fx/12/console", bases))
        assertEquals(JenkinsUi.PastedJob("https://ci.example.com/", "team/app/feature%2Fx"),
            JenkinsUi.parse("https://ci.example.com/blue/organizations/jenkins/team%2Fapp/detail/feature%2Fx/12/pipeline", bases))
        assertEquals(JenkinsUi.PastedJob("https://ci.example.com/", "tools/deploy"),
            JenkinsUi.parse("https://ci.example.com/blue/organizations/jenkins/tools%2Fdeploy/detail/deploy/3/pipeline/", bases))
        assertNull(JenkinsUi.parse("https://other.example.com/job/x/", bases))
    }

    @Test
    fun `blue ocean link of a multibranch build`() {
        val build = BuildInfo("https://ci/job/team/job/app/job/feature%252Fx/7/", 7, BuildResult.SUCCESS, 0, 0, 0, "", "team/app/feature%2Fx",
            "https://ci/job/team/job/app/job/feature%252Fx/", emptySet(), emptyList(), emptyList(), buildClass = "org.jenkinsci.plugins.workflow.job.WorkflowRun")
        assertEquals("https://ci/blue/organizations/jenkins/team%2Fapp/detail/feature%2Fx/7/pipeline",
            JenkinsUi.buildUrl(build, de.pdenis.jenkspotter.settings.BrowserUi.BLUE_OCEAN))
        assertEquals(build.url + "pipeline-graph/", JenkinsUi.buildUrl(build, de.pdenis.jenkspotter.settings.BrowserUi.PIPELINE_GRAPH))
    }

    @Test
    fun `repository names match multibranch projects`() {
        assertEquals("My-App", BranchJobs.repoName("git@github.com:team/My-App.git"))
        assertEquals("my-app", BranchJobs.repoName("https://bitbucket/scm/team/my-app.git/"))
        assertTrue(BranchJobs.sameRepo("My-App", "my_app"))
        assertFalse(BranchJobs.sameRepo("my-app", "my-app-tools"))
    }

    @Test
    fun `remembered parameters survive odd characters`() {
        val values = mapOf("A" to "x=y,z", "B" to "", "C" to "line1\nline2")
        assertEquals(values, JenkSpotterSettings.decodeParams(JenkSpotterSettings.encodeParams(values)))
    }
}
