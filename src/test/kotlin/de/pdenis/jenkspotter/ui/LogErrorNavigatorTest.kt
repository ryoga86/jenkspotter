package de.pdenis.jenkspotter.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class LogErrorNavigatorTest : BasePlatformTestCase() {
    fun `test text printed right after clear stays and its error lines are marked`() {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console as ConsoleViewImpl
        Disposer.register(testRootDisposable, console)
        console.component
        val errors = LogErrorNavigator(console)
        console.print("log of another stage\n", ConsoleViewContentType.NORMAL_OUTPUT)
        waitUntil(console) { !console.hasDeferredOutput() }

        // The order of StagesPanel.print in one event: clear, print, scan.
        console.clear()
        errors.reset()
        console.print("compiling\nERROR: my-Deploy-error job failed\n", ConsoleViewContentType.NORMAL_OUTPUT)
        errors.scan()
        waitUntil(console) { !console.hasDeferredOutput() && errors.count > 0 }

        assertEquals("compiling\nERROR: my-Deploy-error job failed\n", console.editor!!.document.text)
        assertEquals(1, errors.count)
    }

    private fun waitUntil(console: ConsoleViewImpl, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !condition()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertNotNull(console.editor)
    }
}
