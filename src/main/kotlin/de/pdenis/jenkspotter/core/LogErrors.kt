package de.pdenis.jenkspotter.core

/**
 * Finds the lines of a build log that report an error: compiler and test failures, exceptions,
 * failed commands. Used to jump between errors in a log and for the hint on failed builds.
 */
object LogErrors {
    /** Test or error counts above zero: "Failures: 2", "3 failed". */
    private val COUNTED = listOf(
        Regex("""(?i)\b(errors?|failures?|failed)\s*[:=]\s*[1-9]"""),
        Regex("""(?i)\b[1-9]\d* (tests? )?(errors?|failures?|failed)\b"""),
    )

    private val ERRORS = listOf(
        // [ERROR], ERROR:, BUILD FAILED, Finished: FAILURE
        Regex("""\b(ERROR|FATAL|FAILURE|FAILED)\b"""),
        // error: / fatal: / error[E0308]: (gcc, javac, git, rustc, ...)
        Regex("""(?i)(^|[\s\]:])(error|fatal)(\[\w+])?:"""),
        // Kotlin compiler
        Regex("""^e: """),
        // java.lang.IllegalStateException: ..., OutOfMemoryError
        Regex("""\b[A-Z]\w*(Exception|Error)\b(:|$)"""),
        Regex("""^\s*Caused by: """),
        Regex("""Traceback \(most recent call last\)"""),
        // npm up to 9 ("npm ERR!") and from 10 on ("npm error")
        Regex("""^npm (ERR!|error)( |$)"""),
        // Go: failed tests and packages, compile errors
        Regex("""^\s*--- FAIL"""),
        Regex("""^FAIL(\s|$)"""),
        Regex("""^\S+\.go:\d+:\d+: """),
        Regex("""^\* What went wrong:"""),
        Regex("""(?i)\bexit (code|status):? [1-9]"""),
    ) + COUNTED

    /** Counts of zero ("Errors: 0", "0 failed"): no error, unless the line also has a count above zero. */
    private val ZERO_COUNTS = listOf(
        Regex("""(?i)\b(errors?|failures?|failed)\s*[:=]\s*0\b"""),
        Regex("""(?i)\b0 (errors?|failures?|failed)\b"""),
    )

    /** Looks like an error but is none: options with "error" in their name, pipeline step headers. */
    private val NO_ERRORS = listOf(
        Regex("""(?i)(^|\s)-D\S*(error|fail)"""),
        Regex("""^\[Pipeline]"""),
    )

    /** One cheap pass that rules out most lines before the rules above are tried. */
    private val CANDIDATE = Regex("""(?i)err|fail|xception|caused by|traceback|exit|fatal|^e: |went wrong|\.go:\d""")

    /** Lines that only say that the build failed, without the reason. */
    private val GENERIC = listOf(
        Regex("""^Finished: """),
        Regex("""(?i)script returned exit code"""),
        Regex("""^\[(INFO|ERROR)] BUILD FAILURE"""),
        Regex("""^BUILD FAILED"""),
        Regex("""^FAILURE: Build failed"""),
        Regex("""^\[ERROR] *$"""),
        Regex("""(?i)^\[ERROR] (To see the full stack trace|Re-run Maven|For more information|Help 1|-> \[Help)"""),
    )

    /** Why a build was marked unstable: warnings, test counts with failures, the unstable step itself. */
    private val UNSTABLE = listOf(
        Regex("""(?i)\bunstable\b"""),
        Regex("""^\[WARNING]"""),
        Regex("""^WARNING:"""),
        Regex("""(?i)quality gate"""),
        Regex("""(?i)threshold"""),
    ) + COUNTED

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    // Jenkins embeds hyperlink metadata as concealed "ha:" blobs that would show up as noise.
    private val CONSOLE_NOTE = Regex("\u001B\\[8mha:[^\u001B]*\u001B\\[0m")

    /** Console text as Jenkins shows it: without its hidden annotations, with plain line ends. */
    fun stripConsoleNotes(text: String): String = text.replace(CONSOLE_NOTE, "").replace("\r\n", "\n")

    private fun clean(line: String) = if (line.indexOf('\u001B') >= 0) line.replace(ANSI, "") else line

    fun isError(line: String): Boolean = matches(clean(line))

    /** [text]: a line without colour codes. */
    private fun matches(text: String): Boolean {
        if (!CANDIDATE.containsMatchIn(text)) return false
        if (NO_ERRORS.any { it.containsMatchIn(text) }) return false
        if (ZERO_COUNTS.any { it.containsMatchIn(text) } && COUNTED.none { it.containsMatchIn(text) }) return false
        return ERRORS.any { it.containsMatchIn(text) }
    }

    /**
     * The most telling error lines of [log], at most [max], in log order: the last specific ones
     * (the reason is usually right before the build gives up), generic "build failed" lines only
     * when there is nothing else.
     */
    fun summary(log: String, max: Int = 4): List<String> {
        val errors = log.lineSequence().map { clean(it).trim() }.filter { it.isNotEmpty() && matches(it) }.toList()
        val specific = errors.filter { line -> GENERIC.none { it.containsMatchIn(line) } }
        val chosen = (specific.ifEmpty { errors }).distinct().takeLast(max)
        return chosen.map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
    }

    /**
     * For an unstable build: its error lines and the lines that say why it was marked unstable.
     * At most [max], the last ones, in log order.
     */
    fun unstableSummary(log: String, max: Int = 4): List<String> {
        val lines = log.lineSequence().map { clean(it).trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("[Pipeline]") &&
                    (UNSTABLE.any { it.containsMatchIn(line) } || (matches(line) && GENERIC.none { it.containsMatchIn(line) }))
            }
            .toList().distinct().takeLast(max)
        return lines.map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
    }

    private const val MAX_LINE = 200
}
