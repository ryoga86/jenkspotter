package de.pdenis.jenkspotter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogErrorsTest {
    @Test
    fun `recognises common error lines`() {
        listOf(
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin",
            "src/Main.java:12: error: cannot find symbol",
            "e: file:///app/src/Main.kt:3:5 Unresolved reference: foo",
            "java.lang.IllegalStateException: boom",
            "Caused by: java.io.IOException: closed",
            "fatal: could not read Username",
            "npm ERR! code ELIFECYCLE",
            "Traceback (most recent call last):",
            "ERROR: script returned exit code 1",
            "\u001B[31mERROR\u001B[0m something broke",
            "[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0",
            "Tests run: 12, Failures: 0, Errors: 2",
            "npm error code E404",
            "--- FAIL: TestLogin (0.01s)",
            "FAIL\tgithub.com/acme/app/auth\t0.123s",
            "./main.go:12:3: undefined: foo",
            "* What went wrong:",
            "ERROR: process did not complete successfully: exit code: 1",
            "=========== 2 failed, 31 passed in 4.12s ===========",
        ).forEach { assertTrue(it, LogErrors.isError(it)) }
    }

    @Test
    fun `ignores lines that only look like errors`() {
        listOf(
            "Tests run: 12, Failures: 0, Errors: 0, Skipped: 1",
            "0 errors, 3 warnings",
            "[Pipeline] error",
            "mvn -Dmaven.test.failure.ignore=true verify",
            "Building error-handling module",
            "[INFO] BUILD SUCCESS",
            "ok  \tgithub.com/acme/app/auth\t0.123s",
            "=========== 33 passed in 4.12s ===========",
        ).forEach { assertFalse(it, LogErrors.isError(it)) }
        // An option with "error" in its name hides the line; a job name with -D in the middle does not.
        assertTrue(LogErrors.isError("ERROR: my-Deploy-error job failed"))
    }

    @Test
    fun `summary prefers the specific errors before the generic end of the build`() {
        val log = """
            [INFO] Compiling 3 source files
            [ERROR] /src/App.java:[4,8] cannot find symbol
            [ERROR] /src/App.java:[9,1] class, interface, or enum expected
            [INFO] BUILD FAILURE
            [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
            ERROR: script returned exit code 1
            Finished: FAILURE
        """.trimIndent()
        assertEquals(
            listOf("[ERROR] /src/App.java:[4,8] cannot find symbol", "[ERROR] /src/App.java:[9,1] class, interface, or enum expected"),
            LogErrors.summary(log),
        )
    }

    @Test
    fun `summary falls back to generic lines`() {
        assertEquals(listOf("ERROR: script returned exit code 2", "Finished: FAILURE"), LogErrors.summary("step 1\nERROR: script returned exit code 2\nFinished: FAILURE"))
    }

    @Test
    fun `unstable summary names why the build was marked unstable`() {
        val log = """
            [Pipeline] junit
            Recording test results
            [Checks API] No suitable checks publisher found.
            Tests run: 40, Failures: 2, Errors: 0, Skipped: 0
            Build step 'Publish JUnit test result report' changed build result to UNSTABLE
            Finished: UNSTABLE
        """.trimIndent()
        assertEquals(
            listOf("Tests run: 40, Failures: 2, Errors: 0, Skipped: 0", "Build step 'Publish JUnit test result report' changed build result to UNSTABLE", "Finished: UNSTABLE"),
            LogErrors.unstableSummary(log, 3),
        )
    }
}
