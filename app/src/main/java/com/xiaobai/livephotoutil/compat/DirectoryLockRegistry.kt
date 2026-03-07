package com.xiaobai.livephotoutil.compat

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 目录级锁注册表。
 *
 * 用于在多线程/多协程场景下串行化同目录写入，避免输出文件互相覆盖或写入中间态被读取。
 */
internal object DirectoryLockRegistry {
    /**
     * 锁引用计数条目。
     *
     * @property lock 目录互斥锁。
     * @property refCount 使用该锁的调用计数。
     */
    private data class LockEntry(
        val lock: ReentrantLock,
        val refCount: AtomicInteger = AtomicInteger(1)
    )

    /** 目录路径到锁条目的映射。 */
    private val lockMap = ConcurrentHashMap<String, LockEntry>()

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
        val entries = keys.map { key -> acquireEntry(key) }
        val locks = entries.map { it.lock }
        locks.forEach { lock -> lock.lock() }
        try {
            return action()
        } finally {
            for (index in locks.indices.reversed()) {
                locks[index].unlock()
            }
            for (index in keys.indices) {
                releaseEntry(keys[index], entries[index])
            }
        }
    }

    /**
     * 获取指定目录锁条目并增加引用计数。
     *
     * @param key 目录路径键。
     */
    private fun acquireEntry(key: String): LockEntry {
        return lockMap.compute(key) { _, current ->
            if (current == null) {
                LockEntry(lock = ReentrantLock())
            } else {
                current.refCount.incrementAndGet()
                current
            }
        }!!
    }

    /**
     * 释放指定目录锁条目引用，必要时从映射中回收。
     *
     * @param key 目录路径键。
     * @param entry 待释放条目。
     */
    private fun releaseEntry(key: String, entry: LockEntry) {
        lockMap.computeIfPresent(key) { _, current ->
            if (current !== entry) {
                current
            } else {
                val remaining = current.refCount.decrementAndGet()
                if (remaining == 0 && !current.lock.isLocked && !current.lock.hasQueuedThreads()) {
                    null
                } else {
                    current
                }
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
