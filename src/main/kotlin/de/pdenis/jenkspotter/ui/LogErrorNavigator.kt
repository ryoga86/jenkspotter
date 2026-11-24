package de.pdenis.jenkspotter.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.LogErrors
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Error lines of a log console ([LogErrors]): tinted, marked in the scrollbar, and reachable with
 * "Next / Previous Error" (F2 / Shift+F2). Call [scan] after printing and [reset] after clearing.
 */
class LogErrorNavigator(private val console: ConsoleView) {
    private val highlighters = mutableListOf<RangeHighlighter>()

    /** Start of the first line not scanned yet; moves along when lines are cut off at the top. */
    private var scanned: RangeMarker? = null

    private val editor get() = (console as? ConsoleViewImpl)?.editor

    /** Number of error lines found so far. */
    val count: Int get() = highlighters.count { it.isValid }

    fun reset() {
        editor?.markupModel?.let { markup -> highlighters.filter { it.isValid }.forEach(markup::removeHighlighter) }
        highlighters.clear()
        scanned?.dispose()
        scanned = null
    }

    /** Marks the error lines printed since the last call; a line still being written waits for its end. */
    fun scan() {
        (console as? ConsoleViewImpl)?.flushDeferredText()
        val editor = editor ?: return
        (editor.markupModel as? EditorMarkupModel)?.isErrorStripeVisible = true
        val document = editor.document
        val from = scanned?.takeIf { it.isValid && it.startOffset <= document.textLength }?.startOffset ?: 0
        if (from == 0) highlighters.removeAll { !it.isValid }
        var line = document.getLineNumber(from)
        var end = from
        while (line < document.lineCount) {
            val lineEnd = document.getLineEndOffset(line)
            if (lineEnd >= document.textLength) break
            val start = document.getLineStartOffset(line)
            val text = document.charsSequence.subSequence(start, lineEnd).toString()
            if (LogErrors.isError(text)) {
                highlighters += editor.markupModel.addRangeHighlighter(start, lineEnd, HighlighterLayer.WARNING, ATTRIBUTES, HighlighterTargetArea.LINES_IN_RANGE)
                    .apply { errorStripeTooltip = text.trim().take(200) }
            }
            end = lineEnd + 1
            line++
        }
        scanned?.dispose()
        scanned = document.createRangeMarker(end, end)
    }

    /** Moves the caret to the next (or previous) error line after the caret, wrapping around. */
    fun jump(forward: Boolean) {
        scan()
        val editor = editor ?: return
        val starts = highlighters.filter { it.isValid }.map { it.startOffset }.sorted()
        if (starts.isEmpty()) return
        val document = editor.document
        val caretLine = document.getLineNumber(editor.caretModel.offset.coerceAtMost(document.textLength))
        val target = if (forward) {
            starts.firstOrNull { document.getLineNumber(it) > caretLine } ?: starts.first()
        } else {
            starts.lastOrNull { document.getLineNumber(it) < caretLine } ?: starts.last()
        }
        editor.caretModel.moveToOffset(target)
        editor.selectionModel.setSelection(target, document.getLineEndOffset(document.getLineNumber(target)))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    /** "Previous Error" and "Next Error" for the console toolbar, with F2 / Shift+F2 on the console. */
    fun actions(): List<AnAction> = listOf(
        action(forward = false, AllIcons.Actions.PreviousOccurence, KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.SHIFT_DOWN_MASK)),
        action(forward = true, AllIcons.Actions.NextOccurence, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)),
    )

    private fun action(forward: Boolean, icon: javax.swing.Icon, key: KeyStroke) =
        object : DumbAwareAction(JenkSpotterBundle.message(if (forward) "log.error.next" else "log.error.previous"), null, icon) {
            init {
                registerCustomShortcutSet(CustomShortcutSet(key), console.component)
            }

            override fun actionPerformed(e: AnActionEvent) = jump(forward)

            override fun update(e: AnActionEvent) {
                val found = count
                e.presentation.isEnabled = found > 0
                e.presentation.text = JenkSpotterBundle.message(if (forward) "log.error.next" else "log.error.previous") +
                    " (" + JenkSpotterBundle.message("log.error.count", found) + ")"
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    companion object {
        private val ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(0xFCE8E6, 0x4A2B2B)
            errorStripeColor = JBColor(0xE55B5B, 0xC75450)
        }
    }
}
