package de.pdenis.jenkspotter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobMatcherTest {
    private fun score(query: String, name: String, path: String = "") =
        JobMatcher.match(JobMatcher.tokenize(query), name, path)?.score

    @Test
    fun `exact beats prefix beats word start beats infix beats fuzzy`() {
        val names = listOf("a-p-i", "rapid", "customer-api", "api-gateway", "api")
        val ranked = names.sortedByDescending { score("api", it) }
        assertEquals(listOf("api", "api-gateway", "customer-api", "rapid", "a-p-i"), ranked)
    }

    @Test
    fun `fuzzy ranges`() {
        val result = JobMatcher.match(JobMatcher.tokenize("scapi"), "shop-customer-api", "")!!
        assertEquals(listOf(0..0, 5..5, 14..16), result.nameRanges)
    }

    @Test
    fun `tokens are AND and may hit path or name`() {
        val result = JobMatcher.match(JobMatcher.tokenize("aws boot"), "acme-aws-ci-bootstrap", "Infrastructure / acme-aws-ci")!!
        assertTrue(result.nameRanges.isNotEmpty())
        assertNull(JobMatcher.match(JobMatcher.tokenize("aws nope"), "acme-aws-ci-bootstrap", "x"))
    }

    @Test
    fun `path is not matched fuzzily and name wins ties`() {
        assertNull(score("tmx", "build", "team / matrix"))
        val nameHit = score("app", "app", "x")!!
        val pathHit = score("app", "build", "app")!!
        assertTrue(nameHit > pathHit)
    }

    @Test
    fun `case insensitive with camel case word starts`() {
        assertTrue(score("st", "MyServiceTool")!! > score("st", "myservicetool")!!)
    }
}
