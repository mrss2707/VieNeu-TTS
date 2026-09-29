package com.vieneu.mobile.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 11 preset voice — metadata index (H13: chọn voice KHÔNG tạo session).
 * Nạp từ `assets/voices/voices_v3_nano_mobile.json` của package P1.
 */
object VoiceManager {

    @Serializable
    data class Voice(
        val id: String,
        val name: String = id,
        val speakerEmb: List<Float> = emptyList(),
        val style: List<Float> = emptyList(),
        val werReference: Double? = null,
    )

    @Serializable
    data class VoiceFile(
        val schema_version: String = "1",
        val default_voice_id: String = "",
        val voices: List<Voice> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var cached: VoiceFile? = null

    fun loadFromJson(jsonText: String): VoiceFile {
        val f = json.decodeFromString<VoiceFile>(jsonText)
        cached = f
        return f
    }

    fun listPresets(): List<Voice> = cached?.voices.orEmpty()

    fun defaultVoiceId(): String = cached?.default_voice_id.orEmpty()

    /** Chọn voice — chỉ đổi conditioning, không load session mới (H18 subset). */
    fun select(id: String): Voice {
        val f = cached ?: throw EngineError.Internal("VoiceManager chưa load voices JSON")
        return f.voices.find { it.id == id }
            ?: throw EngineError.InvalidInput("Không có voice id=$id")
    }

    fun speakerEmb(id: String): FloatArray = select(id).speakerEmb.toFloatArray()

    fun style(id: String): FloatArray = select(id).style.toFloatArray()
}
