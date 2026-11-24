package de.pdenis.jenkspotter.ui

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color

/** Alternating, subtle row backgrounds of the jobs list and the builds table. */
object Stripes {
    /** Share of the text colour mixed into the list background: just enough to tell rows apart. */
    private const val STRENGTH = 0.025

    /** Background of a row: the selection colour, or every second row slightly tinted. */
    fun background(striped: Boolean, selected: Boolean, hasFocus: Boolean): Color = when {
        selected -> UIUtil.getListBackground(true, hasFocus)
        // Derived from the theme's own background, so it stays close to it in light and dark themes.
        striped -> ColorUtil.mix(UIUtil.getListBackground(), UIUtil.getListForeground(), STRENGTH)
        else -> UIUtil.getListBackground()
    }
}
