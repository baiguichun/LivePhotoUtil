package com.xiaobai.livephotoutil.compat

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LivePhotoCoroutineSdk] 协程入口测试。 */
class LivePhotoCoroutineSdkTest {
    @Test
    fun detectAndNormalizeForCloud_thenRestoreForDevice_worksInSuspendApi() = runBlocking {
        val root = createTempDir("coroutine-sdk")
        try {
            val inputDir = File(root, "input").apply { mkdirs() }
            val image = File(inputDir, "IMG_2001.JPG").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val video = File(inputDir, "IMG_2001.MOV").apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5)) }

            val sdk = LivePhotoCoroutineSdk()
            val canonical = sdk.detectAndNormalizeForCloud(
                candidates = listOf(image, video),
                cloudDir = File(root, "cloud")
            )
            assertTrue(canonical.manifestFile.exists())

            val restored = sdk.restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore")
            )
            assertEquals(ContainerMode.RAW_REPLAY, restored.mode)
            assertTrue(restored.outputFiles.any { it.name == "IMG_2001.JPG" })
            assertTrue(restored.outputFiles.any { it.name == "IMG_2001.MOV" })
        } finally {
            deleteRecursively(root)
        }
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
