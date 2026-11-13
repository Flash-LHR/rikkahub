package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.buildPhoneUseTool
import me.rerere.rikkahub.data.ai.tools.local.phoneUseActionOf
import me.rerere.rikkahub.phoneuse.PhoneApp
import me.rerere.rikkahub.phoneuse.PhoneController
import me.rerere.rikkahub.phoneuse.PhoneElement
import me.rerere.rikkahub.phoneuse.PhoneKey
import me.rerere.rikkahub.phoneuse.PhoneScreen
import me.rerere.rikkahub.phoneuse.PhoneUseManager
import me.rerere.rikkahub.phoneuse.PhoneUseTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseToolTest {
    private val manager = PhoneUseManager()
    private val controller = FakeController(manager)
    private var selfInForeground = false
    private var floatingWindowOn = true
    private val tool: Tool = buildPhoneUseTool(
        ownPackageName = "me.rerere.rikkahub",
        manager = manager,
        saveScreenshot = { "file:///screenshot.jpg" },
        isFloatingWindowOn = { floatingWindowOn },
        isSelfInForeground = { selfInForeground },
    )

    private suspend fun call(build: JsonObjectBuilder.() -> Unit): String =
        tool.execute(buildJsonObject(build)).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    @Test
    fun `fails with guidance when the accessibility service is off`() = runTest {
        val error = runCatching { call { put("action", "observe") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("not enabled"))
    }

    @Test
    fun `refuses to run while the floating window is off`() = runTest {
        manager.attach(controller)
        floatingWindowOn = false
        val error = runCatching {
            call {
                put("action", "press")
                put("key", "home")
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("floating window"))
        assertTrue(controller.keys.isEmpty())
    }

    @Test
    fun `observe lists elements on the grid`() = runTest {
        manager.attach(controller)
        val output = call { put("action", "observe") }
        assertTrue(output.contains("App: Demo (com.demo)"))
        // 中心 (540,1200) 在 1080x2400 的屏幕上是网格的 (500,500)
        assertTrue(output.contains("[0] Button \"Send\" clickable @(500,500)"))
        assertTrue(output.contains("[1] EditText \"Message\" editable focused @(250,900)"))
        // 没有文字的元素用资源 ID 说明用途，有文字的不带
        assertTrue(output.contains("[2] ImageView #btn_more clickable @(879,62)"))
        assertFalse(output.contains("ignored_when_labeled"))
    }

    @Test
    fun `tap on element hits its center in pixels while overlays yield`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        val output = call {
            put("action", "tap")
            put("element", 0)
        }
        assertEquals(listOf(540f, 1200f, 540f, 1200f), controller.gestures.single())
        assertEquals(listOf(true), controller.actingDuringGesture)
        assertFalse(manager.acting.value)
        // 结果直接带回操作后的界面
        assertTrue(output.startsWith("tap done."))
        assertTrue(output.contains("Elements"))
    }

    @Test
    fun `tap by grid coordinates converts to pixels`() = runTest {
        manager.attach(controller)
        // 模型有时把数字写成字符串
        call {
            put("action", "tap")
            put("x", "250")
            put("y", 750)
        }
        assertEquals(listOf(270f, 1800f, 270f, 1800f), controller.gestures.single())
    }

    @Test
    fun `scroll down moves the finger up`() = runTest {
        manager.attach(controller)
        call {
            put("action", "scroll")
            put("direction", "down")
        }
        val (fromX, fromY, toX, toY) = controller.gestures.single()
        assertEquals(fromX, toX)
        assertTrue(fromY > toY)
    }

    @Test
    fun `stale element index is rejected`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        val error = runCatching {
            call {
                put("action", "tap")
                put("element", 9)
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("Observe the screen again"))
        assertTrue(controller.gestures.isEmpty())
    }

    @Test
    fun `refuses to operate while this app is in the foreground except leaving it`() = runTest {
        manager.attach(controller)
        selfInForeground = true
        val error = runCatching {
            call {
                put("action", "tap")
                put("x", 1)
                put("y", 1)
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("foreground"))
        assertTrue(controller.gestures.isEmpty())

        call {
            put("action", "press")
            put("key", "home")
        }
        assertEquals(listOf(PhoneKey.HOME), controller.keys)
        call {
            put("action", "open_app")
            put("app", "demo")
        }
        assertEquals(listOf("com.demo"), controller.opened)
    }

    @Test
    fun `blocked apps cannot be listed or opened`() = runTest {
        manager.attach(controller)
        assertFalse(call { put("action", "list_apps") }.contains("Alipay"))
        val error = runCatching {
            call {
                put("action", "open_app")
                put("app", "Alipay")
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("blocked"))
        assertTrue(controller.opened.isEmpty())
    }

    @Test
    fun `inside a blocked app nothing is read or operated but leaving works`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        // 从别的应用跳转进了支付宝
        controller.visible = listOf("com.eg.android.AlipayGphone")
        listOf("observe", "screenshot", "tap").forEach { action ->
            val error = runCatching {
                call {
                    put("action", action)
                    put("element", 0)
                }
            }.exceptionOrNull()
            assertTrue(action, error!!.message!!.contains("blocked"))
        }
        assertTrue(controller.gestures.isEmpty())

        // 返回可以用，但结果里不带支付宝界面的内容
        val output = call {
            put("action", "press")
            put("key", "back")
        }
        assertEquals(listOf(PhoneKey.BACK), controller.keys)
        assertTrue(output.contains("blocked"))
        assertFalse(output.contains("Send"))
    }

    @Test
    fun `a blocked app that is visible but not on top still blocks everything`() = runTest {
        manager.attach(controller)
        // 分屏或下拉通知栏：最上层是别的应用，支付宝的界面还在屏幕上
        controller.visible = listOf("com.android.systemui", "com.eg.android.AlipayGphone")
        listOf("observe", "screenshot", "tap").forEach { action ->
            val error = runCatching {
                call {
                    put("action", action)
                    put("x", 500)
                    put("y", 500)
                }
            }.exceptionOrNull()
            assertTrue(action, error!!.message!!.contains("blocked"))
        }
        assertTrue(controller.gestures.isEmpty())

        // 操作之后才出现的也不把界面交给模型
        controller.visible = listOf("com.demo")
        controller.afterGesture = { controller.visible = listOf("com.demo", "com.eg.android.AlipayGphone") }
        val output = call {
            put("action", "tap")
            put("x", 500)
            put("y", 500)
        }
        assertTrue(output.contains("blocked"))
        assertFalse(output.contains("Send"))
    }

    @Test
    fun `type with submit sets text then presses enter`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        call {
            put("action", "type")
            put("element", 1)
            put("text", "hi")
            put("submit", true)
        }
        assertEquals(listOf(1 to "hi"), controller.texts)
        assertEquals(listOf(PhoneKey.ENTER), controller.keys)
    }

    @Test
    fun `action name is read from complete input only`() {
        assertEquals("tap", phoneUseActionOf("""{"action":"tap","element":3}"""))
        assertNull(phoneUseActionOf("""{"action":"ta"""))
        assertNull(phoneUseActionOf(""))
    }

    @Test
    fun `screen stays on between phone actions and across agent flow completion until the turn ends`() = runTest {
        manager.attach(controller)
        val turn = PhoneUseTurn()
        assertFalse(manager.keepScreenOn.value)
        try {
            flow {
                call {
                    put("action", "press")
                    put("key", "home")
                }
                emit(Unit)
            }.flowOn(StandardTestDispatcher(testScheduler) + turn).first()

            assertFalse(manager.acting.value)
            assertTrue(manager.keepScreenOn.value)
            // 模型等待超过常见的息屏时间，以及中途压缩后重开 agent flow，都仍属于同一轮。
            delay(60_000)
            assertTrue(manager.keepScreenOn.value)
            withContext(turn) { call { put("action", "observe") } }
        } finally {
            turn.close()
        }
        assertFalse(manager.keepScreenOn.value)
    }

    @Test
    fun `ordinary generation without phone use does not keep the screen on`() = runTest {
        manager.attach(controller)
        val turn = PhoneUseTurn()
        withContext(turn) { delay(60_000) }
        assertFalse(manager.keepScreenOn.value)
        turn.close()
    }

    @Test
    fun `cancelling the turn releases the screen after a phone action`() = runTest {
        manager.attach(controller)
        val job = launch {
            val turn = PhoneUseTurn()
            try {
                withContext(turn) {
                    call { put("action", "observe") }
                    awaitCancellation()
                }
            } finally {
                turn.close()
            }
        }
        runCurrent()
        assertTrue(manager.keepScreenOn.value)
        job.cancelAndJoin()
        assertFalse(manager.keepScreenOn.value)
    }

    @Test
    fun `a failing phone turn releases the screen`() = runTest {
        manager.attach(controller)
        val turn = PhoneUseTurn()
        val failure = runCatching {
            try {
                withContext(turn) {
                    call { put("action", "observe") }
                    error("Provider failed after a phone action")
                }
            } finally {
                turn.close()
            }
        }.exceptionOrNull()
        assertEquals("Provider failed after a phone action", failure?.message)
        assertFalse(manager.keepScreenOn.value)
    }

    @Test
    fun `ending one of two phone turns does not release the other turns screen`() = runTest {
        manager.attach(controller)
        val first = PhoneUseTurn()
        val second = PhoneUseTurn()
        withContext(first) {
            call { put("action", "observe") }
            call { put("action", "observe") }
        }
        withContext(second) { call { put("action", "observe") } }
        first.close()
        first.close()
        assertTrue(manager.keepScreenOn.value)
        second.close()
        assertFalse(manager.keepScreenOn.value)
        // 已结束的 scope 不能被迟到的工具调用重新持有。
        withContext(first) { call { put("action", "observe") } }
        assertFalse(manager.keepScreenOn.value)
    }

    @Test
    fun `service disconnect releases the screen and reconnect restores an active turn`() = runTest {
        manager.attach(controller)
        val turn = PhoneUseTurn()
        withContext(turn) { call { put("action", "observe") } }
        manager.detach(controller)
        assertFalse(manager.keepScreenOn.value)
        manager.attach(controller)
        assertTrue(manager.keepScreenOn.value)
        turn.close()
        assertFalse(manager.keepScreenOn.value)
    }

    @Test
    fun `unavailable phone use cannot acquire the screen`() = runTest {
        val turn = PhoneUseTurn()
        withContext(turn) {
            assertTrue(runCatching { call { put("action", "observe") } }.isFailure)
        }
        manager.attach(controller)
        assertFalse(manager.keepScreenOn.value)
        turn.close()
    }

    private class FakeController(private val manager: PhoneUseManager) : PhoneController {
        val gestures = mutableListOf<List<Float>>()
        val actingDuringGesture = mutableListOf<Boolean>()
        val keys = mutableListOf<PhoneKey>()
        val texts = mutableListOf<Pair<Int?, String>>()
        val opened = mutableListOf<String>()
        var afterGesture: () -> Unit = {}

        // 最上层的在前
        var visible = listOf("com.demo")

        override fun visiblePackages() = visible.toSet()

        override suspend fun captureScreen() = PhoneScreen(
            packageName = visible.first(),
            packages = visible.toSet(),
            appLabel = "Demo",
            width = 1080,
            height = 2400,
            keyboardVisible = false,
            elements = listOf(
                element(0, "Button", "Send", 440, 1100, 640, 1300, clickable = true),
                element(1, "EditText", "Message", 0, 2100, 540, 2220, editable = true, focused = true),
                element(2, "ImageView", "", 900, 100, 1000, 200, clickable = true, resourceId = "btn_more"),
            ),
            truncated = false,
        )

        override suspend fun screenshot(maxSide: Int) = ByteArray(0)

        override suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
            gestures += listOf(fromX, fromY, toX, toY)
            afterGesture()
            actingDuringGesture += manager.acting.value
            return true
        }

        override suspend fun setText(elementIndex: Int?, text: String): Boolean {
            texts += elementIndex to text
            return true
        }

        override suspend fun pressKey(key: PhoneKey): Boolean {
            keys += key
            return true
        }

        override fun listApps() = listOf(
            PhoneApp("com.demo", "Demo App"),
            PhoneApp("com.eg.android.AlipayGphone", "Alipay"),
        )

        override fun openApp(packageName: String): Boolean {
            opened += packageName
            return true
        }

        private fun element(
            index: Int,
            className: String,
            label: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            clickable: Boolean = false,
            editable: Boolean = false,
            focused: Boolean = false,
            resourceId: String? = "ignored_when_labeled",
        ) = PhoneElement(
            index, className, label, resourceId, left, top, right, bottom, clickable, editable, false, null, focused,
        )
    }
}
