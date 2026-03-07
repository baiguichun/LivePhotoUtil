package com.xiaobai.livephotoutil.compat

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LivePhotoCompatEngine] 识别层测试。 */
class LivePhotoCompatEngineTest {
    @Test
    fun detectApplePair_identifiesJpegAndMovPair() {
        val root = createTempDir("engine-apple")
        try {
            val image = File(root, "IMG_9001.JPG").apply { writeBytes(fakeJpeg("apple")) }
            val video = File(root, "IMG_9001.MOV").apply { writeBytes(fakeVideo("qt  ")) }

            val detected = LivePhotoCompatEngine.defaultEngine().detectOrThrow(listOf(image, video))
            assertEquals(DeviceVendor.APPLE, detected.vendor)
            assertEquals(LivePhotoProtocol.APPLE_PAIR, detected.protocol)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectVendorMotionPhoto_identifiesHuaweiTranscodedFile() {
        val root = createTempDir("engine-huawei")
        try {
            val motion = createVendorMotionPhoto(root, DeviceVendor.HUAWEI, "HW_motion")

            val detected = LivePhotoCompatEngine.defaultEngine().detectOrThrow(listOf(motion))
            assertEquals(DeviceVendor.HUAWEI, detected.vendor)
            assertEquals(LivePhotoProtocol.MOTION_PHOTO, detected.protocol)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectVendorMotionPhoto_rejectsMarkerOnlyJpegWithoutXmpOffset() {
        val root = createTempDir("engine-marker-only")
        try {
            val motion = File(root, "bad_motion.jpg")
            val jpeg = fakeJpeg("HwCamera:MotionPhoto=\"1\"")
            val mp4 = fakeVideo("isom")
            motion.writeBytes(jpeg + mp4)

            val detected = LivePhotoCompatEngine.defaultEngine().detect(listOf(motion))
            assertNull(detected)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectVendorMotionPhoto_rejectsMismatchedMicroVideoOffset() {
        val root = createTempDir("engine-offset-mismatch")
        try {
            val motion = createVendorMotionPhoto(root, DeviceVendor.HUAWEI, "offset_bad")
            corruptMicroVideoOffset(motion)

            val detected = LivePhotoCompatEngine.defaultEngine().detect(listOf(motion))
            assertNull(detected)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectGenericPair_rejectsLowConfidenceMismatch() {
        val root = createTempDir("engine-generic-reject")
        try {
            val image = File(root, "cat.jpg").apply { writeBytes(fakeJpeg("plain")) }
            val video = File(root, "movie.mp4").apply { writeBytes(fakeVideo("isom")) }

            val detected = LivePhotoCompatEngine.defaultEngine().detect(listOf(image, video))
            assertNull(detected)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectGenericPair_rejectsAmbiguousCandidatesWithTieScore() {
        val root = createTempDir("engine-generic-ambiguous")
        try {
            val imageA = File(root, "session_alpha_cover.jpg").apply { writeBytes(fakeJpeg("plain")) }
            val imageB = File(root, "session_beta_cover.jpg").apply { writeBytes(fakeJpeg("plain")) }
            val videoA = File(root, "session_gamma_clip.mp4").apply { writeBytes(fakeVideo("isom")) }
            val videoB = File(root, "session_delta_clip.mp4").apply { writeBytes(fakeVideo("isom")) }

            val detected = LivePhotoCompatEngine.defaultEngine().detect(listOf(imageA, imageB, videoA, videoB))
            assertNull(detected)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun detectGenericPair_acceptsMediumConfidenceFallback() {
        val root = createTempDir("engine-generic-fallback")
        try {
            val image = File(root, "capture_session_20260101_A.jpg").apply { writeBytes(fakeJpeg("plain")) }
            val video = File(root, "capture_session_20260101_B.mp4").apply { writeBytes(fakeVideo("isom")) }

            val detected = LivePhotoCompatEngine.defaultEngine().detectOrThrow(listOf(image, video))
            assertEquals(DeviceVendor.UNKNOWN, detected.vendor)
            assertEquals(LivePhotoProtocol.GENERIC_PAIR, detected.protocol)
        } finally {
            deleteRecursively(root)
        }
    }

    /**
     * 基于转码器生成目标厂商 MotionPhoto 测试文件。
     *
     * @param root 测试根目录。
     * @param vendor 目标厂商。
     * @param contentId 内容标识。
     */
    private fun createVendorMotionPhoto(root: File, vendor: DeviceVendor, contentId: String): File {
        val image = File(root, "$contentId.jpg").apply { writeBytes(fakeJpeg("plain")) }
        val video = File(root, "$contentId.mp4").apply { writeBytes(fakeVideo("isom")) }
        val asset = LivePhotoAsset(
            vendor = DeviceVendor.UNKNOWN,
            protocol = LivePhotoProtocol.GENERIC_PAIR,
            image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
            video = MediaSlice(video, 0L, video.length(), "video/mp4"),
            contentId = contentId
        )
        val output = File(root, "out-${vendor.name.lowercase()}")
        val result = LivePhotoTranscoder().transcode(asset, vendor, output)
        return result.outputFiles.single()
    }

    /**
     * 篡改 MotionPhoto 的 `MicroVideoOffset`，用于构造偏移不一致样本。
     *
     * @param file 待篡改文件。
     */
    private fun corruptMicroVideoOffset(file: File) {
        val content = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        val pattern = Regex("GCamera:MicroVideoOffset=\"\\d{10}\"")
        val replaced = content.replaceFirst(pattern, "GCamera:MicroVideoOffset=\"0000000000\"")
        assertTrue("Test fixture must contain MicroVideoOffset.", replaced != content)
        file.writeBytes(replaced.toByteArray(StandardCharsets.ISO_8859_1))
    }

    /**
     * 生成最小可用 JPEG（包含 marker 文本）。
     *
     * @param markerText 需注入的 marker 文本。
     */
    private fun fakeJpeg(markerText: String): ByteArray {
        val marker = markerText.toByteArray(StandardCharsets.US_ASCII)
        val len = marker.size + 2
        val app1 = ByteArray(marker.size + 4)
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = ((len shr 8) and 0xFF).toByte()
        app1[3] = (len and 0xFF).toByte()
        System.arraycopy(marker, 0, app1, 4, marker.size)
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return soi + app1 + eoi
    }

    /**
     * 生成最小可用 MP4/QuickTime ftyp 结构。
     *
     * @param brand ftyp 主品牌（4 字符）。
     */
    private fun fakeVideo(brand: String): ByteArray {
        require(brand.length == 4) { "brand must be 4 chars" }
        val b = brand.toByteArray(StandardCharsets.US_ASCII)
        return byteArrayOf(
            0, 0, 0, 24,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            b[0], b[1], b[2], b[3],
            0, 0, 0, 0,
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 0, 8
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
