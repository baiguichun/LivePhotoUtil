package com.xiaobai.livephotoutil.compat

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DirectoryLockRegistry] 并发行为测试。 */
class DirectoryLockRegistryTest {
    @Test
    fun withDirectoryLocks_serializesSameDirectory() {
        val dir = createTempDir("dir-lock")
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val activeCount = AtomicInteger(0)
            val maxActive = AtomicInteger(0)

            repeat(2) {
                Thread {
                    start.await()
                    DirectoryLockRegistry.withDirectoryLocks(listOf(dir)) {
                        val active = activeCount.incrementAndGet()
                        maxActive.updateAndGet { current -> maxOf(current, active) }
                        Thread.sleep(120)
                        activeCount.decrementAndGet()
                    }
                    done.countDown()
                }.start()
            }

            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, maxActive.get())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun withDirectoryLocks_releasesUnusedEntries() {
        val root = createTempDir("dir-lock-cleanup")
        try {
            val lockMapField = DirectoryLockRegistry::class.java.getDeclaredField("lockMap")
            lockMapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val lockMap = lockMapField.get(DirectoryLockRegistry) as MutableMap<String, *>
            val beforeSize = lockMap.size

            repeat(20) { index ->
                val dir = File(root, "d$index").apply { mkdirs() }
                DirectoryLockRegistry.withDirectoryLocks(listOf(dir)) {
                    // no-op
                }
            }

            assertEquals(beforeSize, lockMap.size)
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
