package de.pdenis.jenkspotter.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JenkinsPathsTest {
    @Test
    fun `job path encodes folders and multibranch branches`() {
        assertEquals("job/team/job/my%20app/job/feature%252Fx/", JenkinsPaths.jobPath("team/my app/feature%2Fx"))
    }

    @Test
    fun `rebase keeps context path of configured base`() {
        assertEquals(
            "https://ci.local/jenkins/job/a/12/",
            JenkinsPaths.rebase("https://ci.local/jenkins", "http://internal:8080/jenkins/job/a/12/"),
        )
        assertEquals("https://ci.local/queue/item/1/", JenkinsPaths.rebase("https://ci.local/", "/queue/item/1"))
    }

    @Test
    fun `build url helpers`() {
        val url = "https://ci.local/job/team/job/app/job/feature%252Fx/17/"
        assertEquals("https://ci.local/job/team/job/app/job/feature%252Fx/", JenkinsPaths.jobUrlFromBuildUrl(url))
        assertEquals(17, JenkinsPaths.buildNumberFromUrl(url))
        assertEquals("team/app/feature%2Fx", JenkinsPaths.fullNameFromUrl(url))
        assertEquals("team / app / feature/x", JenkinsPaths.displayName("team/app/feature%2Fx"))
        assertNull(JenkinsPaths.jobUrlFromBuildUrl("https://ci.local/job/team/"))
    }

    @Test
    fun `view url from nested name`() {
        assertEquals("https://ci.local/view/My%20Jobs/view/Red/", JenkinsPaths.viewUrl("https://ci.local", "My Jobs/Red"))
    }
}
