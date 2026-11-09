package de.pdenis.jenkspotter

/**
 * Settings of the integration tests against the local Docker Jenkins (docker/). They come from the
 * environment (see docker/setup_env.py); without them the integration tests do nothing.
 */
object ItEnv {
    val url: String? = System.getenv("JENKSPOTTER_IT_URL")
    val token: String? = System.getenv("JENKINS_TEST_TOKEN")
    val devPassword: String? = System.getenv("JENKINS_DEV_PASSWORD")
    const val USER = "dev"

    val enabled: Boolean get() = url != null && token != null
}
