package com.xiaobai.livephotoutil.compat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
    fun transcodeToApplePair_usesMp4ForMp4MimeEvenIfSourceVendorIsApple() {
        val root = createTempDir("transcoder-test-mp4")
        try {
            val result = LivePhotoTranscoder().transcode(
                asset(
                    root = root,
                    vendor = DeviceVendor.APPLE,
                    videoMime = "video/mp4",
                    contentId = "sample_mp4"
                ),
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "out")
            )
            val video = result.outputFiles.first { it.name == "sample_mp4.mp4" }
            assertEquals("sample_mp4.mp4", video.name)
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
     * @param contentId 输入内容标识。
     */
    private fun asset(
        root: File,
        vendor: DeviceVendor,
        videoMime: String,
        contentId: String
    ): LivePhotoAsset {
        val image = File(root, "$contentId.jpg")
        val video = File(root, "$contentId.video")
        image.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        video.writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        return LivePhotoAsset(
            vendor = vendor,
            protocol = LivePhotoProtocol.GENERIC_PAIR,
            image = MediaSlice(image, 0L, 3L, "image/jpeg"),
            video = MediaSlice(video, 0L, 3L, videoMime),
            contentId = contentId
        )
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
