package de.lesecuritae.korbuino

/**
 * On-device language model for the voice assistant (Korbunio Plus).
 *
 * The standard build talks to Gemini Nano through Android AICore (e.g. Galaxy S25, Pixel 9/10);
 * the F-Droid build ships no Google libraries and always reports [Status.UNAVAILABLE].
 * Nothing leaves the phone: prompts and answers stay on the device.
 */
interface OnDeviceAi {
    enum class Status { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    suspend fun status(): Status

    /** Starts or awaits the model download; true when the model is ready afterwards. */
    suspend fun download(): Boolean

    /** Model answer for [prompt], or null when the model failed or is not available. */
    suspend fun generate(prompt: String): String?
}

/** Used where no model exists (F-Droid build, tests). */
object NoOnDeviceAi : OnDeviceAi {
    override suspend fun status() = OnDeviceAi.Status.UNAVAILABLE
    override suspend fun download() = false
    override suspend fun generate(prompt: String): String? = null
}
