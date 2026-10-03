package com.alexcantini.blockstv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** Normalizes controller directions; remote D-pad events keep their native repeat. */
internal class ControllerInput(private val down: (Int, KeyEvent) -> Boolean,
                               private val up: (Int, KeyEvent) -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val keys = mutableSetOf<Int>()
    private var axisDirection: Int? = null
    private var activeDirection: Int? = null
    private var deviceId = -1
    private var repeatCount = 0
    private val repeat = object : Runnable {
        override fun run() {
            val direction = activeDirection ?: return
            if (direction != KeyEvent.KEYCODE_DPAD_LEFT && direction != KeyEvent.KEYCODE_DPAD_RIGHT) return
            emit(direction, true, ++repeatCount)
            handler.postDelayed(this, 100L)
        }
    }

    fun key(event: KeyEvent): Boolean {
        val code = normalize(event.keyCode)
        if (code !in DIRECTIONS || (!isController(event) && code == event.keyCode)) return false
        selectDevice(event.deviceId)
        if (event.action == KeyEvent.ACTION_DOWN) keys.add(code) else keys.remove(code)
        updateDirection()
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
        selectDevice(event.deviceId)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val usingHat = abs(hx) > 0.5f || abs(hy) > 0.5f
        val x = if (usingHat) hx else axis(event, MotionEvent.AXIS_X)
        val y = if (usingHat) hy else axis(event, MotionEvent.AXIS_Y)
        val threshold = if (usingHat) 0.5f else if (axisDirection != null) 0.35f else 0.55f
        axisDirection = when {
            y > threshold -> KeyEvent.KEYCODE_DPAD_DOWN // Keep the downward diagonal filter.
            y < -threshold && abs(y) >= abs(x) -> KeyEvent.KEYCODE_DPAD_UP
            x < -threshold -> KeyEvent.KEYCODE_DPAD_LEFT
            x > threshold -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
        val hadDirection = activeDirection != null
        updateDirection()
        return hadDirection || axisDirection != null
    }

    fun reset() {
        handler.removeCallbacks(repeat)
        activeDirection?.let { emit(it, false) }
        activeDirection = null
        axisDirection = null
        keys.clear()
        repeatCount = 0
        deviceId = -1
    }

    private fun selectDevice(id: Int) {
        if (deviceId != id) { reset(); deviceId = id }
    }

    private fun updateDirection() {
        // A controller may report the same D-pad through both keys and HAT axes.
        val next = when {
            KeyEvent.KEYCODE_DPAD_DOWN in keys || axisDirection == KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            axisDirection != null -> axisDirection
            else -> DIRECTIONS.firstOrNull { it in keys }
        }
        if (next == activeDirection) return
        handler.removeCallbacks(repeat)
        activeDirection?.let { emit(it, false) }
        activeDirection = next
        repeatCount = 0
        next?.let {
            emit(it, true)
            if (it == KeyEvent.KEYCODE_DPAD_LEFT || it == KeyEvent.KEYCODE_DPAD_RIGHT) handler.postDelayed(repeat, 250L)
        }
    }

    private fun emit(code: Int, pressed: Boolean, repeats: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeats, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
        if (pressed) down(code, event) else up(code, event)
    }

    private fun axis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
        return if (abs(value) <= flat) 0f else value
    }

    companion object {
        private val DIRECTIONS = listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        fun isController(event: KeyEvent): Boolean =
            event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true ||
            event.device?.supportsSource(InputDevice.SOURCE_JOYSTICK) == true ||
            event.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
            event.keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16

        fun normalize(code: Int): Int = when (code) {
            KeyEvent.KEYCODE_A -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_D -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_S -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_ESCAPE -> KeyEvent.KEYCODE_BACK
            KeyEvent.KEYCODE_BUTTON_1 -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_2 -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_3 -> KeyEvent.KEYCODE_BUTTON_X
            KeyEvent.KEYCODE_BUTTON_4 -> KeyEvent.KEYCODE_BUTTON_Y
            else -> code
        }
    }
}
