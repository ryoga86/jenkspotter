package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.JobRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoMatchTest {
    @Test
    fun `https ssh and scp urls of one repository give the same key`() {
        val urls = listOf(
            "https://git.example.com/scm/Team/App.git",
            "ssh://git@git.example.com:7999/team/app.git",
            "git@github.com:team/app.git",
            "https://user@github.com/team/app/",
        )
        assertEquals(setOf("team/app"), urls.map { RepoMatch.key(it) }.toSet())
        assertNull(RepoMatch.key("https://host/"))
    }

    @Test
    fun `jobs match by repository url, without one by name, and keep their folders`() {
        val match = RepoMatch(listOf("git@host:team/app.git"), listOf("app"))
        val jobs = listOf(
            JobRef("build", "team/build", "u1", "hudson.model.FreeStyleProject", repos = listOf("https://host/scm/team/app.git")),
            JobRef("other", "team/other", "u2", "hudson.model.FreeStyleProject", repos = listOf("https://host/scm/team/other.git")),
            JobRef("team", "team", "u3", "com.cloudbees.hudson.plugins.folder.Folder"),
            JobRef("app", "app", "u4", "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"),
            JobRef("main", "app/main", "u5", "org.jenkinsci.plugins.workflow.job.WorkflowJob"),
            // Same name as the project, but Jenkins knows it builds another repository.
            JobRef("app", "misc/app", "u6", "hudson.model.FreeStyleProject", repos = listOf("git@host:other/app-fork.git")),
        )
        assertEquals(setOf("u1", "u3", "u4", "u5"), match.filter(jobs))
        assertFalse(RepoMatch(emptyList(), emptyList()).matches(jobs[0]))
        assertTrue(RepoMatch(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `upstream path takes the longest chain leading to a job and survives cycles`() {
        fun job(name: String, vararg next: String) = JobRef(name, name, "u/$name", "hudson.model.FreeStyleProject", next.toList())
        val state = JobIndexService.IndexState(listOf(
            job("build", "test"), job("test", "deploy"), job("hotfix", "deploy"), job("deploy", "build"),
        ))
        val deploy = state.byFullName.getValue("deploy")
        assertEquals(listOf("build", "test"), JobIndexService.upstreamPath(state, deploy).map { it.name })
        assertEquals(emptyList<JobRef>(), JobIndexService.upstreamPath(state, job("lonely")))
    }
}
