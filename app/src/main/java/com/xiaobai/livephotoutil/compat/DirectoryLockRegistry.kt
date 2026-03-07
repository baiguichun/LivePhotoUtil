package com.xiaobai.livephotoutil.compat

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 目录级锁注册表。
 *
 * 用于在多线程/多协程场景下串行化同目录写入，避免输出文件互相覆盖或写入中间态被读取。
 */
internal object DirectoryLockRegistry {
    /** 目录路径到锁对象的映射。 */
    private val lockMap = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 在持有给定目录锁的情况下执行动作。
     *
     * 多个目录会按路径排序后加锁，避免死锁。
     *
     * @param directories 需要加锁的目录列表。
     * @param action 在锁保护下执行的动作。
     */
    fun <T> withDirectoryLocks(directories: List<File>, action: () -> T): T {
        val keys = directories
            .map { normalizePath(it) }
            .distinct()
            .sorted()
        val locks = keys.map { key -> lockMap.computeIfAbsent(key) { ReentrantLock() } }
        locks.forEach { lock -> lock.lock() }
        try {
            return action()
        } finally {
            for (index in locks.indices.reversed()) {
                locks[index].unlock()
            }
        }
    }

    /**
     * 将目录转换为稳定路径键。
     *
     * @param directory 目录对象。
     */
    private fun normalizePath(directory: File): String {
        return runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
    }
}
