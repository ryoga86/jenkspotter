package de.pdenis.jenkspotter.notify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import kotlin.math.log10

/** Plays the result chime off the EDT. Missing audio devices (headless Linux, RDP) are ignored. */
object SoundPlayer {
    private val warned = AtomicBoolean(false)

    fun play(result: BuildResult) {
        val settings = JenkSpotterSettings.getInstance().current
        if (!settings.soundEnabled) return
        val custom = when (result) {
            BuildResult.SUCCESS -> settings.customSuccessSound
            BuildResult.FAILURE, BuildResult.UNSTABLE -> settings.customFailureSound
            else -> ""
        }
        playWith(result, custom, settings.soundVolume)
    }

    /** Plays with explicit values; used by the settings preview before anything is saved. */
    fun playWith(result: BuildResult, customPath: String, volume: Int) {
        val resource = when (result) {
            BuildResult.SUCCESS -> "success.wav"
            BuildResult.FAILURE, BuildResult.UNSTABLE -> "failure.wav"
            else -> "aborted.wav"
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val stream: InputStream? = customPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { File(it).inputStream() }
                ?: SoundPlayer::class.java.getResourceAsStream("/sounds/$resource")
            stream?.let { playStream(it, volume) }
        }
    }

    private fun playStream(input: InputStream, volume: Int) {
        try {
            val audio = AudioSystem.getAudioInputStream(BufferedInputStream(input))
            val clip = AudioSystem.getClip()
            clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) clip.close() }
            clip.open(audio)
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val db = if (volume <= 0) gain.minimum else (20 * log10(volume / 100.0)).toFloat()
                gain.value = db.coerceIn(gain.minimum, gain.maximum)
            }
            clip.start()
        } catch (e: Exception) {
            if (warned.compareAndSet(false, true)) thisLogger().info("JenkSpotter cannot play sounds: ${e.message}")
        } finally {
            runCatching { input.close() }
        }
    }
}
