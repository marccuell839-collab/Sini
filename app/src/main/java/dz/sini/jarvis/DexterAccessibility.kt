package dz.sini.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DexterAccessibility : AccessibilityService() {
    companion object { @Volatile var active: DexterAccessibility? = null }
    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { active = null; super.onDestroy() }
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun tapText(label: String): Boolean {
        val window = rootInActiveWindow ?: return false
        for (found in window.findAccessibilityNodeInfosByText(label)) {
            var node: AccessibilityNodeInfo? = found
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                node = node.parent
            }
        }
        return false
    }
    fun typeText(value: String): Boolean {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!field.isEditable) return false
        val data = Bundle()
        data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, data)
    }
    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (!pending.isEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable && node.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
        }
        return false
    }
}