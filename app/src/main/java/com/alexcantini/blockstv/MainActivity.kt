package com.alexcantini.blockstv

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var gameView: BlocksView
    private lateinit var controllerInput: ControllerInput
    private lateinit var inputManager: InputManager
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = releaseController()
        override fun onInputDeviceRemoved(deviceId: Int) = releaseController()
    }

    private fun releaseController() {
        controllerInput.reset()
        gameView.suspendInput()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        gameView = BlocksView(this) { finish() }
        controllerInput = ControllerInput(gameView::handleKeyDown, gameView::handleKeyUp)
        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        setContentView(gameView)
        gameView.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("BlocksTVKey", "DOWN keyCode=$keyCode name=${KeyEvent.keyCodeToString(keyCode)} repeat=${event.repeatCount}")
        if (controllerInput.key(event)) return true
        return if (gameView.handleKeyDown(ControllerInput.normalize(keyCode), event)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("BlocksTVKey", "UP keyCode=$keyCode name=${KeyEvent.keyCodeToString(keyCode)}")
        if (controllerInput.key(event)) return true
        return if (gameView.handleKeyUp(ControllerInput.normalize(keyCode), event)) true else super.onKeyUp(keyCode, event)
    }


    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val directionHandled = controllerInput.motion(event)
        val triggerHandled = gameView.handleGenericMotionEvent(event)
        return if (directionHandled || triggerHandled) true else super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::controllerInput.isInitialized) {
            controllerInput.reset()
            gameView.suspendInput()
        }
    }

    override fun onPause() {
        controllerInput.reset()
        gameView.suspendInput()
        super.onPause()
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(deviceListener)
        controllerInput.reset()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        // Non usiamo il back di sistema per uscire direttamente durante il gioco.
        // La gestione passa dal popup di conferma della view.
        if (!gameView.handleBackFallback()) {
            super.onBackPressed()
        }
    }
}
