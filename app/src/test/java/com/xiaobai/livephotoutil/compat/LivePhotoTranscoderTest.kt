package com.xiaobai.livephotoutil.compat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [LivePhotoTranscoder] 行为测试。 */
class LivePhotoTranscoderTest {
    @Test
    fun transcodeToApplePair_usesMovForQuickTimeMime() {
        val root = createTempDir("transcoder-test-qt")
        try {
            val result = LivePhotoTranscoder().transcode(
                asset(
                    root = root,
                    vendor = DeviceVendor.UNKNOWN,
                    videoMime = "video/quicktime",
                    videoBytes = isoBmffVideo("qt  "),
                    contentId = "sample_qt"
                ),
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "out")
            )
            val video = result.outputFiles.first { it.name == "sample_qt.mov" }
            assertEquals("sample_qt.mov", video.name)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun transcodeToApplePair_usesMovForMp4Mime() {
        val root = createTempDir("transcoder-test-mp4")
        try {
            val result = LivePhotoTranscoder().transcode(
                asset(
                    root = root,
                    vendor = DeviceVendor.APPLE,
                    videoMime = "video/mp4",
                    videoBytes = isoBmffVideo("isom"),
                    contentId = "sample_mp4"
                ),
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "out")
            )
            val video = result.outputFiles.first { it.name == "sample_mp4.mov" }
            assertEquals("sample_mp4.mov", video.name)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun transcodeToMotionPhoto_rewritesQuickTimeBrandToMp4Compatible() {
        val root = createTempDir("transcoder-test-motion-brand")
        try {
            val result = LivePhotoTranscoder().transcode(
                asset(
                    root = root,
                    vendor = DeviceVendor.APPLE,
                    videoMime = "video/quicktime",
                    videoBytes = isoBmffVideo("qt  "),
                    contentId = "sample_motion"
                ),
                targetVendor = DeviceVendor.HUAWEI,
                outputDir = File(root, "out")
            )
            val motion = result.outputFiles.single()
            val offset = MediaIO.findEmbeddedMp4Offset(motion)
            assertTrue(offset > 0L)
            val normalizedBrand = readAscii(motion, offset + 8, 4)
            assertEquals("isom", normalizedBrand)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun transcodeToMotionPhoto_rejectsNonIsoBmffVideo() {
        val root = createTempDir("transcoder-test-non-iso")
        try {
            try {
                LivePhotoTranscoder().transcode(
                    asset(
                        root = root,
                        vendor = DeviceVendor.UNKNOWN,
                        videoMime = "video/mp4",
                        videoBytes = byteArrayOf(0x11, 0x22, 0x33),
                        contentId = "sample_invalid"
                    ),
                    targetVendor = DeviceVendor.GOOGLE,
                    outputDir = File(root, "out")
                )
                fail("Expected transcode to reject non-ISO BMFF video.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("ISO BMFF", ignoreCase = true) == true)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    /**
     * 构造测试输入资产。
     *
     * @param root 测试临时目录。
     * @param vendor 输入资产厂商。
     * @param videoMime 输入视频 MIME。
     * @param videoBytes 输入视频字节。
     * @param contentId 输入内容标识。
     */
    private fun asset(
        root: File,
        vendor: DeviceVendor,
        videoMime: String,
        videoBytes: ByteArray,
        contentId: String
    ): LivePhotoAsset {
        val image = File(root, "$contentId.jpg")
        val video = File(root, "$contentId.video")
        image.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        video.writeBytes(videoBytes)
        return LivePhotoAsset(
            vendor = vendor,
            protocol = LivePhotoProtocol.GENERIC_PAIR,
            image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
            video = MediaSlice(video, 0L, video.length(), videoMime),
            contentId = contentId
        )
    }

    /**
     * 生成最小可用的 ISO BMFF 视频样本字节。
     *
     * @param majorBrand `ftyp` 主品牌（4 字符）。
     */
    private fun isoBmffVideo(majorBrand: String): ByteArray {
        require(majorBrand.length == 4) { "majorBrand must be 4 chars." }
        val bytes = ByteArray(32)
        bytes[0] = 0x00
        bytes[1] = 0x00
        bytes[2] = 0x00
        bytes[3] = 0x18
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        bytes[8] = majorBrand[0].code.toByte()
        bytes[9] = majorBrand[1].code.toByte()
        bytes[10] = majorBrand[2].code.toByte()
        bytes[11] = majorBrand[3].code.toByte()
        bytes[12] = 0x00
        bytes[13] = 0x00
        bytes[14] = 0x00
        bytes[15] = 0x00
        bytes[16] = 'i'.code.toByte()
        bytes[17] = 's'.code.toByte()
        bytes[18] = 'o'.code.toByte()
        bytes[19] = 'm'.code.toByte()
        bytes[20] = 'm'.code.toByte()
        bytes[21] = 'p'.code.toByte()
        bytes[22] = '4'.code.toByte()
        bytes[23] = '2'.code.toByte()
        bytes[24] = 0x00
        bytes[25] = 0x00
        bytes[26] = 0x00
        bytes[27] = 0x08
        bytes[28] = 'm'.code.toByte()
        bytes[29] = 'd'.code.toByte()
        bytes[30] = 'a'.code.toByte()
        bytes[31] = 't'.code.toByte()
        return bytes
    }

    /**
     * 从文件指定偏移读取 ASCII 字符串。
     *
     * @param file 源文件。
     * @param offset 读取偏移。
     * @param length 读取长度。
     */
    private fun readAscii(file: File, offset: Long, length: Int): String {
        val bytes = file.readBytes().copyOfRange(offset.toInt(), offset.toInt() + length)
        return String(bytes, Charsets.US_ASCII)
    }

    /**
     * 创建测试临时目录。
     *
     * @param prefix 目录名前缀。
     */
    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param path 待删除目录。
     */
    private fun deleteRecursively(path: File) {
        if (!path.exists()) return
        path.walkBottomUp().forEach { it.delete() }
    }
}
