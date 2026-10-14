package com.shinobi.app

import android.media.MediaCodecList

/**
 * What this phone's video decoders can actually play.
 *
 * Picking a rung the device cannot hardware-decode is what made quality switching laggy or
 * throw: an old 2–3 GB phone asked for 1440p/4K VP9 or AV1 falls to software decoding (a
 * slideshow) or fails to instantiate a codec at all (the error panel). Before a stream is
 * offered or auto-selected it is checked here against the device's own decoder list —
 * the same gate the big open-source clients use — so every listed quality applies instantly
 * and cleanly. Results are cached per (codec, size, rate); probing is cheap and never on the
 * critical path more than once per rung.
 */
object DeviceCodec {
    /** Below this, assume playable: every Android device decodes 480p-class video. */
    private const val ALWAYS_OK_HEIGHT = 480

    private val cache = HashMap<String, Boolean>()

    /** [mimeType] e.g. "video/avc"; null means unknown, answered from the height alone. */
    @Synchronized
    fun canDecode(mimeType: String?, width: Int, height: Int, fps: Int): Boolean {
        if (height <= 0) return false
        if (height <= ALWAYS_OK_HEIGHT) return true
        val mime = mimeType ?: return height <= 1080
        val key = "$mime|$width|$height|$fps"
        cache[key]?.let { return it }
        val ok = probe(mime, width, height, fps)
        cache[key] = ok
        return ok
    }

    private fun isSoftwareDecoder(name: String): Boolean =
        name.startsWith("OMX.google.", ignoreCase = true) || name.startsWith("c2.android.", ignoreCase = true)

    private fun videoCaps(info: android.media.MediaCodecInfo, mime: String) =
        info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
            ?.let { type -> runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() }

    private fun probe(mime: String, width: Int, height: Int, fps: Int): Boolean {
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val w = if (width > 0) width else (height * 16 / 9)
            val rate = fps.coerceIn(24, 120).toDouble()
            var softwareOnly = false
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val video = videoCaps(info, mime) ?: continue
                if (!video.areSizeAndRateSupported(w, height, rate)) continue
                if (isSoftwareDecoder(info.name)) {
                    softwareOnly = true
                } else {
                    return@runCatching true
                }
            }
            // Only software decoders claimed it: playable, but slow — callers may prefer a lower rung.
            softwareOnly
        }.getOrDefault(height <= 1080)
    }

    /** True when some hardware decoder handles this rung; unknown formats stay eligible. */
    @Synchronized
    fun hardwareAccelerated(mimeType: String?, width: Int, height: Int, fps: Int): Boolean {
        if (mimeType == null || height <= ALWAYS_OK_HEIGHT) return true
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val w = if (width > 0) width else (height * 16 / 9)
            val rate = fps.coerceIn(24, 120).toDouble()
            for (info in list.codecInfos) {
                if (info.isEncoder || isSoftwareDecoder(info.name)) continue
                val video = videoCaps(info, mimeType) ?: continue
                if (video.areSizeAndRateSupported(w, height, rate)) return@runCatching true
            }
            false
        }.getOrDefault(true)
    }

    /** MIME type for one of YouTube's video streams, from its codec string or container. */
    fun mimeOf(codec: String?, containerWebm: Boolean): String? = when {
        codec == null -> if (containerWebm) "video/x-vnd.on2.vp9" else "video/avc"
        codec.startsWith("avc", ignoreCase = true) -> "video/avc"
        codec.startsWith("vp09", ignoreCase = true) || codec.startsWith("vp9", ignoreCase = true) -> "video/x-vnd.on2.vp9"
        codec.startsWith("vp08", ignoreCase = true) || codec.startsWith("vp8", ignoreCase = true) -> "video/x-vnd.on2.vp8"
        codec.startsWith("av01", ignoreCase = true) -> "video/av01"
        codec.startsWith("hev", ignoreCase = true) || codec.startsWith("hvc", ignoreCase = true) -> "video/hevc"
        else -> if (containerWebm) "video/x-vnd.on2.vp9" else "video/avc"
    }
}
