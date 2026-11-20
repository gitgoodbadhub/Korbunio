package de.lesecuritae.korbuino

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.first

/** Gemini Nano via ML Kit GenAI (Android AICore). Every call is guarded: on any device without it, it simply reports unavailable. */
object OnDeviceAiProvider {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): OnDeviceAi = GeminiNano

    private object GeminiNano : OnDeviceAi {
        private const val TAG = "GeminiNano"
        private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

        override suspend fun status(): OnDeviceAi.Status {
            val client = model ?: return OnDeviceAi.Status.UNAVAILABLE
            return when (runCatching { client.checkStatus() }.getOrElse { Log.w(TAG, "status", it); FeatureStatus.UNAVAILABLE }) {
                FeatureStatus.AVAILABLE -> OnDeviceAi.Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> OnDeviceAi.Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> OnDeviceAi.Status.DOWNLOADING
                else -> OnDeviceAi.Status.UNAVAILABLE
            }
        }

        override suspend fun download(): Boolean {
            val client = model ?: return false
            return runCatching {
                client.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed } is DownloadStatus.DownloadCompleted
            }.getOrElse { Log.w(TAG, "download", it); false }
        }

        override suspend fun generate(prompt: String): String? {
            val client = model ?: return null
            return runCatching {
                client.generateContent(generateContentRequest(TextPart(prompt)) {
                    temperature = 0.1f
                    maxOutputTokens = 400
                }).candidates.firstOrNull()?.text
            }.getOrElse { Log.w(TAG, "generate", it); null }
        }
    }
}
