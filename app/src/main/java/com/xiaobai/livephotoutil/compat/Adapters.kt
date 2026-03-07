package com.xiaobai.livephotoutil.compat

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 图片与视频的最佳配对结果。
 *
 * @property image 匹配到的图片文件。
 * @property video 匹配到的视频文件。
 * @property score 配对分值。
 */
private data class PairCandidate(
    val image: File,
    val video: File,
    val score: Int
)

/**
 * 在候选图片与视频中选出分值最高的一组配对。
 *
 * @param images 图片候选列表。
 * @param videos 视频候选列表。
 * @param extraScore 视频额外加分规则。
 */
private fun findBestPair(
    images: List<File>,
    videos: List<File>,
    extraScore: (File) -> Int
): PairCandidate? {
    if (images.isEmpty() || videos.isEmpty()) return null
    val stemCache = HashMap<File, String>(images.size + videos.size)
    val normCache = HashMap<File, String>(images.size + videos.size)
    fun stem(file: File): String {
        return stemCache[file] ?: NameHeuristics.stem(file).lowercase(Locale.ROOT).also { stemCache[file] = it }
    }
    fun norm(file: File): String {
        return normCache[file] ?: NameHeuristics.normalizedStem(file).also { normCache[file] = it }
    }

    var best: PairCandidate? = null
    images.forEach { image ->
        videos.forEach { video ->
            var score = pairScoreFast(stem(image), norm(image), stem(video), norm(video))
            score += extraScore(video)
            val current = best
            if (current == null || score > current.score) {
                best = PairCandidate(image = image, video = video, score = score)
            }
        }
    }
    return best
}

/**
 * 使用预计算的 stem/normalized 值计算配对分值。
 *
 * @param imageStem 图片 stem（小写）。
 * @param imageNorm 图片归一化 stem。
 * @param videoStem 视频 stem（小写）。
 * @param videoNorm 视频归一化 stem。
 */
private fun pairScoreFast(imageStem: String, imageNorm: String, videoStem: String, videoNorm: String): Int {
    if (imageStem == videoStem) return 4
    if (imageNorm.isNotBlank() && imageNorm == videoNorm) return 3
    if (imageStem.startsWith(videoStem) || videoStem.startsWith(imageStem)) return 2
    val lcp = longestCommonPrefixFast(imageStem, videoStem)
    return if (lcp >= 6) 1 else 0
}

/**
 * 计算两个字符串的最长公共前缀长度。
 *
 * @param a 字符串 A。
 * @param b 字符串 B。
 */
private fun longestCommonPrefixFast(a: String, b: String): Int {
    val length = minOf(a.length, b.length)
    var index = 0
    while (index < length && a[index] == b[index]) index++
    return index
}

/** LivePhoto 协议探测适配器。 */
interface LivePhotoAdapter {
    /** 适配器唯一标识。 */
    val id: String
    /** 探测优先级，越大越先执行。 */
    val priority: Int
    /**
     * 尝试从输入文件中识别 LivePhoto 资源。
     *
     * @param input 待探测输入上下文。
     * @return 识别成功返回 [LivePhotoAsset]，否则返回 `null`。
     */
    fun probe(input: ProbeInput): LivePhotoAsset?
}

/** Apple 双文件（图片+视频）协议探测器。 */
class ApplePairAdapter : LivePhotoAdapter {
    /** 适配器标识。 */
    override val id: String = "apple-pair"
    /** 适配器优先级。 */
    override val priority: Int = VendorProfiles.profileOf(DeviceVendor.APPLE).priority

    /**
     * 基于 MIME 和文件名相似度寻找最可信的图片/视频配对。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        val images = mutableListOf<File>()
        val videos = mutableListOf<File>()
        input.candidates.forEach { file ->
            val mime = input.mimeOf(file)
            when {
                MediaIO.isImage(mime) -> images.add(file)
                MediaIO.isVideo(mime) -> videos.add(file)
            }
        }
        val bestPair = findBestPair(images, videos) { video ->
            if (input.mimeOf(video) == "video/quicktime") 1 else 0
        }
        if (bestPair == null || bestPair.score < 2) return null
        val id = NameHeuristics.normalizedStem(bestPair.image)
            .ifBlank { NameHeuristics.stem(bestPair.image) }
        return LivePhotoAsset(
            vendor = DeviceVendor.APPLE,
            protocol = LivePhotoProtocol.APPLE_PAIR,
            image = MediaIO.wholeFileSlice(bestPair.image),
            video = MediaIO.wholeFileSlice(bestPair.video),
            contentId = id,
            notes = listOf("Apple pair adapter selected.")
        )
    }
}

/**
 * 某一厂商 MotionPhoto（单 JPEG 内嵌 MP4）协议探测器。
 *
 * @property profile 目标厂商 profile。
 */
class VendorMotionPhotoAdapter(private val profile: VendorProfile) : LivePhotoAdapter {
    /** 适配器标识。 */
    override val id: String = "motion-${profile.vendor.name.lowercase()}"
    /** 适配器优先级。 */
    override val priority: Int = profile.priority

    /**
     * 根据厂商 marker + 内嵌 MP4 偏移识别 MotionPhoto。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        input.candidates.forEach { file ->
            if (input.mimeOf(file) != "image/jpeg") return@forEach
            val prefix = input.prefixOf(file, 256 * 1024)
            if (prefix.isEmpty()) return@forEach
            val text = String(prefix, StandardCharsets.ISO_8859_1)
            if (!hasMarkers(text, profile.detectMarkers)) return@forEach
            val offset = MediaIO.findEmbeddedMp4Offset(file)
            if (offset <= 0L) return@forEach
            val size = file.length()
            if (size - offset < 12L) return@forEach
            val id = NameHeuristics.normalizedStem(file).ifBlank { NameHeuristics.stem(file) }
            return LivePhotoAsset(
                vendor = profile.vendor,
                protocol = LivePhotoProtocol.MOTION_PHOTO,
                image = MediaSlice(file, 0L, offset, "image/jpeg"),
                video = MediaSlice(file, offset, size - offset, "video/mp4"),
                contentId = id,
                notes = listOf("${profile.vendor} motion photo adapter selected.")
            )
        }
        return null
    }

    /**
     * 判断文本中是否出现该厂商的任一标记。
     *
     * @param text 待匹配文本。
     * @param markers 厂商标记列表。
     */
    private fun hasMarkers(text: String, markers: List<String>): Boolean {
        return markers.any { marker -> text.contains(marker) }
    }
}

/** 通用兜底探测器：只要能组成图片+视频配对就可识别。 */
class GenericPairAdapter : LivePhotoAdapter {
    /** 适配器标识。 */
    override val id: String = "generic-pair"
    /** 适配器优先级。 */
    override val priority: Int = 100

    /**
     * 在未命中厂商特定规则时提供最大兼容的兜底识别。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        val images = mutableListOf<File>()
        val videos = mutableListOf<File>()
        input.candidates.forEach { file ->
            val mime = input.mimeOf(file)
            when {
                MediaIO.isImage(mime) -> images.add(file)
                MediaIO.isVideo(mime) -> videos.add(file)
            }
        }
        val bestPair = findBestPair(images, videos) { 0 } ?: return null
        return LivePhotoAsset(
            vendor = DeviceVendor.UNKNOWN,
            protocol = LivePhotoProtocol.GENERIC_PAIR,
            image = MediaIO.wholeFileSlice(bestPair.image),
            video = MediaIO.wholeFileSlice(bestPair.video),
            contentId = NameHeuristics.normalizedStem(bestPair.image).ifBlank { NameHeuristics.stem(bestPair.image) },
            notes = listOf("Generic pair fallback selected.")
        )
    }
}
