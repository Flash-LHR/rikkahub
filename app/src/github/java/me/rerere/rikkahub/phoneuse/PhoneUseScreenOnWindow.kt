package me.rerere.rikkahub.phoneuse

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 独立于聊天气泡的常亮窗口：截图和手势会让气泡透明，切换面板也会隐藏窗口，不能靠它们持续持有屏幕。
 * 窗口只有一个透明像素，不接收触摸或焦点；使用已有的无障碍权限，不申请额外权限，也不唤醒已锁屏的手机。
 */
internal class PhoneUseScreenOnWindow(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            val previous = view ?: return
            view = null
            runCatching { windowManager.removeViewImmediate(previous) }
                .onFailure { Log.w("PhoneUseScreenOn", "Failed to remove screen-on window", it) }
            return
        }
        if (view != null) return
        val window = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "RikkaHub Phone Use screen on"
        }
        try {
            windowManager.addView(window, params)
            view = window
        } catch (e: Exception) {
            Log.w("PhoneUseScreenOn", "Failed to add screen-on window", e)
        }
    }
}
