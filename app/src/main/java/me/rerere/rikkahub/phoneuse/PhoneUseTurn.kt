package me.rerere.rikkahub.phoneuse

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 一轮生成第一次使用 Phone Use 后，持续保持亮屏，直到整轮结束（包括两次工具调用之间的等待）。 */
internal class PhoneUseTurn : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<PhoneUseTurn>

    private val managers = mutableSetOf<PhoneUseManager>()
    private var closed = false

    @Synchronized
    fun keepScreenOn(manager: PhoneUseManager) {
        if (!closed && managers.add(manager)) manager.acquireScreenOn(this)
    }

    @Synchronized
    override fun close() {
        closed = true
        managers.forEach { it.releaseScreenOn(this) }
        managers.clear()
    }
}
