package de.pdenis.jenkspotter

import com.intellij.DynamicBundle
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.JenkSpotterBundle"

/**
 * UI texts. In Wizarding Mode keys present in JenkSpotterWizardBundle override the regular text;
 * keys missing there fall back to the regular bundle.
 */
object JenkSpotterBundle {
    private val regular = DynamicBundle(JenkSpotterBundle::class.java, BUNDLE)
    private val wizard = DynamicBundle(JenkSpotterBundle::class.java, "messages.JenkSpotterWizardBundle")

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        val wizardingMode = runCatching { JenkSpotterSettings.getInstance().current.wizardingMode }.getOrDefault(false)
        if (wizardingMode && wizard.containsKey(key)) return wizard.getMessage(key, *params)
        return regular.getMessage(key, *params)
    }
}
