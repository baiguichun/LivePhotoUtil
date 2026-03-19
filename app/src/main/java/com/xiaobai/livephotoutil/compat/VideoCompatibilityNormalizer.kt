package com.xiaobai.livephotoutil.compat

import java.io.File
import java.io.RandomAccessFile

/**
 * 视频归一化目标容器类型。
 */
enum class VideoContainerTarget {
    /** 目标为 MP4 兼容容器。 */
    MP4,
    /** 目标为 QuickTime 兼容容器。 */
    QUICKTIME
}

/**
 * 非 ISO BMFF 输入的视频归一化扩展点。
 *
 * 当 SDK 检测到输入视频切片不是 ISO BMFF（例如 WebM/3GP 等）时，
 * 可通过该接口接入业务侧转码能力（如 Media3 Transformer、FFmpeg 封装等），
 * 把视频先转为目标容器后再由 SDK 继续封装。
 */
interface VideoCompatibilityNormalizer {
    /**
     * 把输入视频归一化为目标容器。
     *
     * 返回值若为 `null`，表示当前 normalizer 无法处理该输入。
     *
     * @param source 输入视频切片。
     * @param targetContainer 目标容器类型。
     * @param workingDir 临时工作目录（由 SDK 提供，可写入中间文件）。
     */
    fun normalize(
        source: MediaSlice,
        targetContainer: VideoContainerTarget,
        workingDir: File
    ): MediaSlice?
}

/**
 * 默认空实现：不做任何视频归一化。
 */
object NoopVideoCompatibilityNormalizer : VideoCompatibilityNormalizer {
    /**
     * 默认返回 `null`，表示不处理。
     *
     * @param source 输入视频切片。
     * @param targetContainer 目标容器类型。
     * @param workingDir 临时工作目录。
     */
    override fun normalize(
        source: MediaSlice,
        targetContainer: VideoContainerTarget,
        workingDir: File
    ): MediaSlice? = null
}

/**
 * 默认视频归一化实现。
 *
 * 该实现不做重编码，只做“头部污染修复”：
 * 当视频前缀存在垃圾字节导致 `ftyp` 不在切片起点时，尝试定位头部 `ftyp`
 * 并返回从 `ftyp` 开始的新切片，提升弱损坏样本的兼容性。
 */
object DefaultVideoCompatibilityNormalizer : VideoCompatibilityNormalizer {
    /** `ftyp` 扫描窗口上限（字节）。 */
    private const val MAX_SCAN_BYTES = 8L * 1024L * 1024L

    /**
     * 尝试定位 `ftyp` 并返回修复后的切片。
     *
     * @param source 输入视频切片。
     * @param targetContainer 目标容器类型。
     * @param workingDir 临时工作目录（该实现不使用）。
     */
    override fun normalize(
        source: MediaSlice,
        targetContainer: VideoContainerTarget,
        workingDir: File
    ): MediaSlice? {
        if (!source.sourceFile.exists() || !source.sourceFile.isFile) return null
        if (source.length < 16L) return null
        val relativeOffset = findFtypOffset(source) ?: return null
        if (relativeOffset <= 0L) return null
        val normalizedOffset = source.offset + relativeOffset
        val normalizedLength = source.length - relativeOffset
        if (normalizedLength < 16L) return null
        val mime = when (targetContainer) {
            VideoContainerTarget.MP4 -> "video/mp4"
            VideoContainerTarget.QUICKTIME -> "video/quicktime"
        }
        val normalized = MediaSlice(
            sourceFile = source.sourceFile,
            offset = normalizedOffset,
            length = normalizedLength,
            mimeType = mime
        )
        if (!MediaIO.isIsoBmffSlice(normalized)) return null
        return normalized
    }

    /**
     * 在切片头部查找 `ftyp` 开始位置（返回相对偏移）。
     *
     * @param source 输入视频切片。
     */
    private fun findFtypOffset(source: MediaSlice): Long? {
        val scanLimit = minOf(source.length, MAX_SCAN_BYTES)
        RandomAccessFile(source.sourceFile, "r").use { raf ->
            val buffer = ByteArray(8192)
            val carry = ByteArray(7)
            var carryLength = 0
            var scanned = 0L
            while (scanned < scanLimit) {
                val toRead = minOf(buffer.size.toLong(), scanLimit - scanned).toInt()
                raf.seek(source.offset + scanned)
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                val merged = ByteArray(carryLength + read)
                System.arraycopy(carry, 0, merged, 0, carryLength)
                System.arraycopy(buffer, 0, merged, carryLength, read)
                val mergedStart = scanned - carryLength
                for (i in 4 until merged.size - 3) {
                    if (merged[i] == 'f'.code.toByte()
                        && merged[i + 1] == 't'.code.toByte()
                        && merged[i + 2] == 'y'.code.toByte()
                        && merged[i + 3] == 'p'.code.toByte()
                    ) {
                        val boxStart = mergedStart + i - 4
                        if (boxStart in 1 until source.length) return boxStart
                    }
                }
                carryLength = minOf(carry.size, merged.size)
                System.arraycopy(merged, merged.size - carryLength, carry, 0, carryLength)
                scanned += read
            }
        }
        return null
    }
}
