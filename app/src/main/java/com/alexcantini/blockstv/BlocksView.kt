package com.alexcantini.blockstv

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class BlocksView(
    context: Context,
    private val exitApp: () -> Unit
) : View(context) {

    private enum class Screen { HOME, LEVEL_PICKER, GAME }

    companion object {
        // La caduta veloce resta gestita dal gioco.
        // Gli spostamenti laterali usano invece direttamente il repeat nativo TCL/Android.
        private const val SOFT_DROP_REPEAT_MS = 35L
        private const val GAMEPAD_HORIZONTAL_CONFIRM_MS = 40L
        private const val TCL_EXIT_KEYCODE = 4095
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Paint dedicato alla Home: nessun alpha, filtro colore o overlay.
    // L'immagine approvata viene resa direttamente, pixel-for-pixel come sorgente.
    private val homePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        alpha = 255
        colorFilter = null
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val homeBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.home_screen)
    }

    private var screen = Screen.HOME
    private var homeSelection = 0
    private var selectedLevel = 6
    private var engine: GameEngine? = null

    private var paused = false
    private var exitDialog = false
    private var exitDialogSelection = 0 // 0 = ANNULLA, 1 = ESCI

    private var softDropHeld = false

    // Filtro dedicato al gamepad: quando il D-pad viene premuto verso il basso,
    // eventuali piccoli/diagonali impulsi laterali vengono ignorati. Il primo
    // impulso orizzontale viene confermato dopo pochi ms, così un DOWN quasi
    // simultaneo può annullarlo senza alterare il telecomando TCL.
    private var gamepadDownHeld = false
    private var pendingGamepadHorizontal: Runnable? = null

    // L2/R2 del gamepad sono assi analogici su questo controller. I latch separati
    // evitano ripetizioni: L2 = pausa/riprendi, R2 = funzione EXIT del telecomando.
    private var gamepadL2TriggerHeld = false
    private var gamepadR2TriggerHeld = false
    private var gamepadL2KeyHeld = false
    private var gamepadR2KeyHeld = false
    private var lastTclExitTime = -1000L

    private val bg = Color.rgb(5, 9, 16)
    private val panel = Color.rgb(12, 22, 36)
    private val panel2 = Color.rgb(17, 31, 49)
    private val border = Color.rgb(52, 105, 155)
    private val cyan = Color.rgb(43, 195, 255)
    private val white = Color.rgb(235, 241, 248)
    private val muted = Color.rgb(148, 164, 184)

    private val gravityRunnable = object : Runnable {
        override fun run() {
            val e = engine ?: return
            if (!isGameActive() || softDropHeld || e.gameOver) return
            e.stepDown()
            invalidate()
            if (!e.gameOver && isGameActive()) {
                postDelayed(this, e.gravityDelayMs().toLong())
            }
        }
    }

    private val softDropRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isGameActive() || !softDropHeld) return
            val e = engine ?: return
            if (!e.gameOver) {
                e.softDropStep()
                invalidate()
            }
            if (!e.gameOver && softDropHeld && isGameActive()) {
                postDelayed(this, SOFT_DROP_REPEAT_MS)
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (screen) {
            // Home v0.8: niente griglia, tinta, dimming o altri layer dietro/sopra lo sfondo.
            // Disegniamo direttamente l'immagine approvata.
            Screen.HOME -> drawHome(canvas)
            Screen.LEVEL_PICKER -> {
                drawHome(canvas)
                drawLevelPicker(canvas)
            }
            Screen.GAME -> {
                canvas.drawColor(bg)
                drawBackdrop(canvas)
                drawGame(canvas)
            }
        }
        // Niente loop di redraw a 60 fps. La view viene invalidata solo quando cambia qualcosa.
    }

    override fun onDetachedFromWindow() {
        stopAllScheduledInput()
        removeCallbacks(gravityRunnable)
        super.onDetachedFromWindow()
    }

    private fun drawBackdrop(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(26, 80, 140, 190)
        val step = (width / 40f).coerceAtLeast(24f)
        var x = 0f
        while (x < width) {
            c.drawLine(x, 0f, x, height.toFloat(), paint)
            x += step
        }
        var y = 0f
        while (y < height) {
            c.drawLine(0f, y, width.toFloat(), y, paint)
            y += step
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawHome(c: Canvas) {
        // Home v0.7: usa la grafica neon approvata come sfondo 16:9.
        // L'immagine contiene già logo, pulsanti e credito "Developed by Alex Cantini - 2026".
        val src = Rect(0, 0, homeBitmap.width, homeBitmap.height)
        val dst = Rect(0, 0, width, height)
        // Render diretto dell'immagine, senza usare il Paint condiviso dal gameplay.
        c.drawBitmap(homeBitmap, src, dst, homePaint)

        // Evidenziamo solo il pulsante selezionato senza ridisegnare la grafica.
        // Coordinate normalizzate sulla composizione 16:9 dell'immagine approvata.
        val playRect = RectF(width * 0.325f, height * 0.453f, width * 0.662f, height * 0.600f)
        val exitRect = RectF(width * 0.325f, height * 0.619f, width * 0.662f, height * 0.767f)
        val selected = if (homeSelection == 0) playRect else exitRect

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (height * 0.006f).coerceAtLeast(4f)
        paint.color = Color.WHITE
        c.drawRoundRect(selected, height * 0.025f, height * 0.025f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawLogo(c: Canvas, cx: Float, baselineTop: Float, size: Float) {
        val letters = "BLOCKSTV"
        val colors = intArrayOf(
            Color.rgb(34, 116, 255), Color.rgb(255, 161, 28), Color.rgb(255, 62, 55),
            Color.rgb(41, 213, 94), Color.rgb(34, 198, 245), Color.rgb(194, 62, 255)
        )
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.LEFT
        val widths = FloatArray(letters.length) { textPaint.measureText(letters[it].toString()) }
        val total = widths.sum() + size * 0.06f * (letters.length - 1)
        var x = cx - total / 2f
        val y = baselineTop + size
        for (i in letters.indices) {
            textPaint.color = Color.BLACK
            c.drawText(letters[i].toString(), x + 4f, y + 6f, textPaint)
            textPaint.color = colors[i % colors.size]
            c.drawText(letters[i].toString(), x, y, textPaint)
            x += widths[i] + size * 0.06f
        }
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, selected: Boolean) {
        paint.color = if (selected) Color.rgb(37, 76, 111) else panel2
        paint.style = Paint.Style.FILL
        c.drawRoundRect(r, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) 4f else 2f
        paint.color = if (selected) Color.WHITE else border
        c.drawRoundRect(r, 16f, 16f, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = r.height() * 0.40f
        textPaint.color = white
        val fm = textPaint.fontMetrics
        val ty = r.centerY() - (fm.ascent + fm.descent) / 2f
        c.drawText(label, r.centerX(), ty, textPaint)
    }

    private fun drawLevelPicker(c: Canvas) {
        drawDim(c)
        val w = width * 0.52f
        val h = height * 0.50f
        val r = RectF(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2)
        drawPanel(c, r, 22f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = white
        textPaint.textSize = h * 0.09f
        c.drawText("LIVELLO DI PARTENZA", r.centerX(), r.top + h * 0.16f, textPaint)
        textPaint.color = muted
        textPaint.textSize = h * 0.05f
        c.drawText("1 lento   •   10 velocissimo   •   predefinito 6", r.centerX(), r.top + h * 0.25f, textPaint)

        val cols = 5
        val pad = w * 0.08f
        val gap = w * 0.018f
        val cellW = (w - pad * 2 - gap * 4) / cols
        val cellH = h * 0.18f
        val startY = r.top + h * 0.36f
        for (i in 1..10) {
            val row = (i - 1) / cols
            val col = (i - 1) % cols
            val x = r.left + pad + col * (cellW + gap)
            val y = startY + row * (cellH + gap)
            drawButton(c, RectF(x, y, x + cellW, y + cellH), i.toString(), selectedLevel == i)
        }
        textPaint.color = muted
        textPaint.textSize = h * 0.045f
        c.drawText("D-PAD seleziona   •   OK avvia   •   EXIT annulla", r.centerX(), r.bottom - h * 0.08f, textPaint)
    }

    private fun drawGame(c: Canvas) {
        val e = engine ?: return
        val margin = width * 0.025f
        val leftW = width * 0.21f
        val rightW = width * 0.23f
        val boardLeft = margin + leftW + width * 0.018f
        val boardRight = width - margin - rightW - width * 0.018f
        val boardTop = height * 0.045f
        val boardBottom = height * 0.955f
        val boardRect = RectF(boardLeft, boardTop, boardRight, boardBottom)
        drawPanel(c, boardRect, 18f)

        val innerPad = min(width, height) * 0.012f
        val inner = RectF(boardRect.left + innerPad, boardRect.top + innerPad, boardRect.right - innerPad, boardRect.bottom - innerPad)
        val cell = min(inner.width() / GameEngine.COLS, inner.height() / GameEngine.ROWS)
        val gridW = cell * GameEngine.COLS
        val gridH = cell * GameEngine.ROWS
        val gx = inner.centerX() - gridW / 2
        val gy = inner.centerY() - gridH / 2
        val grid = RectF(gx, gy, gx + gridW, gy + gridH)
        paint.color = Color.rgb(2, 5, 9)
        c.drawRect(grid, paint)
        drawGrid(c, grid, cell)

        for (y in 0 until GameEngine.ROWS) {
            for (x in 0 until GameEngine.COLS) {
                val v = e.board[y][x]
                if (v != 0) drawBlock(c, gx + x * cell, gy + y * cell, cell, e.colorFor(v - 1))
            }
        }

        val p = e.current
        for (cc in e.cellsFor()) {
            val x = p.x + cc.x
            val y = p.y + cc.y
            if (y >= 0) drawBlock(c, gx + x * cell, gy + y * cell, cell, e.colorFor(p.type))
        }

        val left = RectF(margin, boardTop, margin + leftW, boardBottom)
        drawStatPanels(c, left, e)
        val right = RectF(width - margin - rightW, boardTop, width - margin, boardBottom)
        drawRightPanel(c, right, e)

        if (paused && !exitDialog && !e.gameOver) drawPauseOverlay(c, boardRect)
        if (e.gameOver) drawGameOver(c, boardRect)
        if (exitDialog) drawExitDialog(c)
    }

    private fun drawStatPanels(c: Canvas, area: RectF, e: GameEngine) {
        val gap = area.height() * 0.018f
        val h = (area.height() - gap * 3) / 4f
        val labels = arrayOf("SCORE", "LEVEL", "LINES", "NEXT")
        val values = arrayOf(e.score.toString(), e.level.toString().padStart(2, '0'), e.lines.toString().padStart(3, '0'), "")
        for (i in 0..3) {
            val r = RectF(area.left, area.top + i * (h + gap), area.right, area.top + i * (h + gap) + h)
            drawPanel(c, r, 16f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = white
            textPaint.textSize = h * 0.18f
            c.drawText(labels[i], r.centerX(), r.top + h * 0.24f, textPaint)
            if (i < 3) {
                textPaint.textSize = h * 0.34f
                textPaint.color = when (i) {
                    0 -> cyan
                    1 -> Color.rgb(105, 235, 63)
                    else -> Color.rgb(255, 177, 36)
                }
                c.drawText(values[i], r.centerX(), r.top + h * 0.68f, textPaint)
            } else {
                val cells = e.cellsForType(e.nextType)
                val box = min(r.width(), r.height()) * 0.18f
                val originX = r.centerX() - box * 2
                val originY = r.centerY() - box * 0.2f
                for (cc in cells) drawBlock(c, originX + cc.x * box, originY + cc.y * box, box, e.colorFor(e.nextType))
            }
        }
    }

    private fun drawRightPanel(c: Canvas, area: RectF, e: GameEngine) {
        val logoH = area.height() * 0.20f
        val logoR = RectF(area.left, area.top, area.right, area.top + logoH)
        drawPanel(c, logoR, 16f)
        drawLogo(c, logoR.centerX(), logoR.top + logoH * 0.18f, logoH * 0.34f)

        val levelR = RectF(area.left, logoR.bottom + area.height() * 0.02f, area.right, logoR.bottom + area.height() * 0.15f)
        drawPanel(c, levelR, 14f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = levelR.height() * 0.36f
        textPaint.color = white
        c.drawText(
            "LIVELLO  ${e.level.toString().padStart(2, '0')}",
            levelR.centerX(),
            levelR.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2,
            textPaint
        )

        val ctrl = RectF(area.left, levelR.bottom + area.height() * 0.02f, area.right, area.bottom)
        drawPanel(c, ctrl, 16f)

        val iconX = ctrl.left + ctrl.width() * 0.19f
        val labelX = ctrl.left + ctrl.width() * 0.42f
        val ys = floatArrayOf(0.16f, 0.34f, 0.52f, 0.70f, 0.87f)
        val icons = arrayOf("↔", "↓", "↑", "▶Ⅱ", "EXIT")
        val labels = arrayOf("MUOVI", "CADUTA VELOCE", "RUOTA", "PAUSA", "ESCI")

        for (i in ys.indices) {
            val yy = ctrl.top + ctrl.height() * ys[i]

            if (i == 4) {
                drawExitKeycap(c, iconX, yy, ctrl)
            } else {
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.LTGRAY
                textPaint.textSize = ctrl.height() * if (i == 3) 0.060f else 0.072f
                c.drawText(icons[i], iconX, centeredBaseline(yy, textPaint), textPaint)
            }

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = white
            // "CADUTA VELOCE" più piccola per restare interamente dentro il pannello.
            textPaint.textSize = ctrl.height() * if (i == 1) 0.046f else 0.055f
            c.drawText(labels[i], labelX, centeredBaseline(yy, textPaint), textPaint)
        }
    }

    private fun drawExitKeycap(c: Canvas, cx: Float, cy: Float, ctrl: RectF) {
        val keyW = ctrl.width() * 0.22f
        val keyH = ctrl.height() * 0.075f
        val r = RectF(cx - keyW / 2f, cy - keyH / 2f, cx + keyW / 2f, cy + keyH / 2f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(18, 31, 48)
        c.drawRoundRect(r, keyH * 0.20f, keyH * 0.20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.LTGRAY
        c.drawRoundRect(r, keyH * 0.20f, keyH * 0.20f, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.LTGRAY
        textPaint.textSize = keyH * 0.43f
        c.drawText("EXIT", cx, centeredBaseline(cy, textPaint), textPaint)
    }

    private fun centeredBaseline(centerY: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawGrid(c: Canvas, r: RectF, cell: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(29, 40, 52)
        for (x in 0..GameEngine.COLS) c.drawLine(r.left + x * cell, r.top, r.left + x * cell, r.bottom, paint)
        for (y in 0..GameEngine.ROWS) c.drawLine(r.left, r.top + y * cell, r.right, r.top + y * cell, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBlock(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val pad = maxOf(1.2f, size * 0.045f)
        val r = RectF(x + pad, y + pad, x + size - pad, y + size - pad)
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawRoundRect(r, size * .08f, size * .08f, paint)
        paint.color = lighten(color, 1.35f)
        c.drawRect(r.left + size * .08f, r.top + size * .08f, r.right - size * .08f, r.top + size * .18f, paint)
        paint.color = darken(color, .63f)
        c.drawRect(r.left + size * .08f, r.bottom - size * .16f, r.right - size * .08f, r.bottom - size * .08f, paint)
    }

    private fun drawPauseOverlay(c: Canvas, board: RectF) {
        paint.color = Color.argb(188, 0, 0, 0)
        c.drawRoundRect(board, 18f, 18f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = white
        textPaint.textSize = height * .075f
        c.drawText("PAUSA", board.centerX(), board.centerY(), textPaint)
        textPaint.color = muted
        textPaint.textSize = height * .028f
        c.drawText("Premi  ▶Ⅱ  per riprendere", board.centerX(), board.centerY() + height * .07f, textPaint)
    }

    private fun drawGameOver(c: Canvas, board: RectF) {
        paint.color = Color.argb(205, 0, 0, 0)
        c.drawRoundRect(board, 18f, 18f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 78, 78)
        textPaint.textSize = height * .065f
        c.drawText("GAME OVER", board.centerX(), board.centerY() - height * .02f, textPaint)
        textPaint.color = white
        textPaint.textSize = height * .026f
        c.drawText("OK nuova partita   •   EXIT home", board.centerX(), board.centerY() + height * .06f, textPaint)
    }

    private fun drawExitDialog(c: Canvas) {
        drawDim(c)
        val w = width * .46f
        val h = height * .34f
        val r = RectF(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2)
        drawPanel(c, r, 20f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = white
        textPaint.textSize = h * .16f
        c.drawText("USCIRE DALLA PARTITA?", r.centerX(), r.top + h * .28f, textPaint)
        textPaint.color = muted
        textPaint.textSize = h * .075f
        c.drawText("La partita corrente andrà persa.", r.centerX(), r.top + h * .47f, textPaint)
        val gap = w * .05f
        val bw = (w - gap * 3) / 2
        val y = r.top + h * .63f
        val bh = h * .22f
        drawButton(c, RectF(r.left + gap, y, r.left + gap + bw, y + bh), "ANNULLA", exitDialogSelection == 0)
        drawButton(c, RectF(r.left + gap * 2 + bw, y, r.left + gap * 2 + bw * 2, y + bh), "ESCI", exitDialogSelection == 1)
    }

    private fun drawPanel(c: Canvas, r: RectF, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = border
        c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDim(c: Canvas) {
        paint.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun handleKeyDown(inputCode: Int, event: KeyEvent): Boolean {
        val keyCode = if (screen != Screen.GAME && inputCode in listOf(
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y))
            KeyEvent.KEYCODE_DPAD_CENTER else inputCode
        val firstPress = event.repeatCount == 0
        val isGamepad = ControllerInput.isController(event)

        // Sul TCL il tasto EXIT reale è il keyCode proprietario 4095.
        // Durante una partita il firmware può inviare subito dopo anche KEYCODE_BACK (4):
        // lo filtriamo per 500 ms; BACK standard usa poi lo stesso flusso di EXIT.
        if (keyCode == KeyEvent.KEYCODE_BACK && SystemClock.uptimeMillis() - lastTclExitTime < 500L) return true
        if (keyCode == TCL_EXIT_KEYCODE && firstPress) lastTclExitTime = SystemClock.uptimeMillis()

        if (isExitKey(keyCode)) {
            if (!firstPress) return true
            when (screen) {
                Screen.HOME -> {
                    exitApp()
                    return true
                }
                Screen.LEVEL_PICKER -> {
                    screen = Screen.HOME
                    invalidate()
                    return true
                }
                Screen.GAME -> {
                    if (engine?.gameOver == true) {
                        goHomeFromGame()
                        return true
                    }
                    if (exitDialog) closeExitDialog() else openExitDialog()
                    return true
                }
            }
        }

        when (screen) {
            Screen.HOME -> {
                if (!firstPress) return true
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        homeSelection = 1 - homeSelection
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (homeSelection == 0) {
                            selectedLevel = 6
                            screen = Screen.LEVEL_PICKER
                        } else {
                            exitApp()
                        }
                        invalidate()
                        return true
                    }
                }
            }

            Screen.LEVEL_PICKER -> {
                if (!firstPress) return true
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        selectedLevel = if (selectedLevel == 1) 10 else selectedLevel - 1
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selectedLevel = if (selectedLevel == 10) 1 else selectedLevel + 1
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        selectedLevel = if (selectedLevel > 5) selectedLevel - 5 else selectedLevel + 5
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        selectedLevel = if (selectedLevel <= 5) selectedLevel + 5 else selectedLevel - 5
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        startGame(selectedLevel)
                        return true
                    }
                }
            }

            Screen.GAME -> {
                // Grilletti gamepad: L2 = pausa/riprendi, R2 = EXIT.
                // Li gestiamo prima degli stati GAME OVER/PAUSA per mantenerli sempre disponibili.
                if (isGamepad && keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                    if (firstPress && !gamepadL2KeyHeld) {
                        gamepadL2KeyHeld = true
                        if (!gamepadL2TriggerHeld && !exitDialog && engine?.gameOver != true) togglePause()
                    }
                    return true
                }

                if (isGamepad && keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                    if (firstPress && !gamepadR2KeyHeld) {
                        gamepadR2KeyHeld = true
                        if (!gamepadR2TriggerHeld) handleGamepadExit()
                    }
                    return true
                }

                if (exitDialog) {
                    if (!firstPress) return true
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            exitDialogSelection = 1 - exitDialogSelection
                            invalidate()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
                            if (exitDialogSelection == 1) goHomeFromGame() else closeExitDialog()
                            return true
                        }
                    }
                    return true
                }

                val e = engine ?: return true
                if (e.gameOver) {
                    if (!firstPress) return true
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
                            startGame(selectedLevel)
                        }
                    }
                    return true
                }


                if (isPauseKey(keyCode)) {
                    if (firstPress) togglePause()
                    return true
                }

                // Durante il gioco il tasto OK/ENTER non ha alcuna funzione.
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    return true
                }

                if (paused) return true

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // Telecomando invariato. Sul gamepad ritardiamo solo il primo
                        // impulso laterale di pochi ms: se nello stesso gesto arriva DOWN
                        // (tipico D-pad sensibile/diagonale), lo scartiamo.
                        if (isGamepad) {
                            if (gamepadDownHeld) return true
                            if (firstPress) {
                                scheduleGamepadHorizontal(-1)
                            } else {
                                cancelPendingGamepadHorizontal()
                                e.move(-1)
                                invalidate()
                            }
                        } else {
                            e.move(-1)
                            invalidate()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isGamepad) {
                            if (gamepadDownHeld) return true
                            if (firstPress) {
                                scheduleGamepadHorizontal(1)
                            } else {
                                cancelPendingGamepadHorizontal()
                                e.move(1)
                                invalidate()
                            }
                        } else {
                            e.move(1)
                            invalidate()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isGamepad) {
                            gamepadDownHeld = true
                            cancelPendingGamepadHorizontal()
                        }
                        if (firstPress) startSoftDrop()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // SU ruota su telecomando, D-pad e stick sinistro.
                        if (firstPress) {
                            e.rotate()
                            invalidate()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_X,
                    KeyEvent.KEYCODE_BUTTON_Y -> {
                        // I quattro tasti colorati del gamepad hanno tutti la stessa funzione: rotazione.
                        if (isGamepad && firstPress) {
                            e.rotate()
                            invalidate()
                        }
                        return isGamepad
                    }
                }
            }
        }
        return false
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val isGamepad = ControllerInput.isController(event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isGamepad) gamepadDownHeld = false
                stopSoftDrop()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> if (isGamepad) {
                gamepadL2KeyHeld = false
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> if (isGamepad) {
                gamepadR2KeyHeld = false
                return true
            }
            KeyEvent.KEYCODE_BACK -> return true
        }
        return isPauseKey(keyCode) || isExitKey(keyCode)
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val isGamepad = (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
            (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        if (!isGamepad || screen != Screen.GAME) return false

        // Mappatura verificata sul VA-003 in modalità 3:
        // L2 arriva sull'asse BRAKE, R2 sull'asse GAS.
        // LTRIGGER/RTRIGGER restano solo come fallback compatibile.
        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_BRAKE), event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        var handled = false

        if (l2 > 0.50f && !gamepadL2TriggerHeld) {
            gamepadL2TriggerHeld = true
            if (!gamepadL2KeyHeld && !exitDialog && engine?.gameOver != true) togglePause()
            handled = true
        } else if (l2 < 0.25f && gamepadL2TriggerHeld) {
            gamepadL2TriggerHeld = false
            handled = true
        }

        if (r2 > 0.50f && !gamepadR2TriggerHeld) {
            gamepadR2TriggerHeld = true
            if (!gamepadR2KeyHeld) handleGamepadExit()
            handled = true
        } else if (r2 < 0.25f && gamepadR2TriggerHeld) {
            gamepadR2TriggerHeld = false
            handled = true
        }

        return handled || l2 > 0.50f || r2 > 0.50f
    }

    private fun handleGamepadExit() {
        if (screen != Screen.GAME) return
        if (engine?.gameOver == true) {
            goHomeFromGame()
            return
        }
        if (exitDialog) closeExitDialog() else openExitDialog()
    }

    private fun scheduleGamepadHorizontal(dx: Int) {
        cancelPendingGamepadHorizontal()
        val action = Runnable {
            pendingGamepadHorizontal = null
            if (!gamepadDownHeld && isGameActive()) {
                engine?.move(dx)
                invalidate()
            }
        }
        pendingGamepadHorizontal = action
        postDelayed(action, GAMEPAD_HORIZONTAL_CONFIRM_MS)
    }

    private fun cancelPendingGamepadHorizontal() {
        pendingGamepadHorizontal?.let { removeCallbacks(it) }
        pendingGamepadHorizontal = null
    }

    fun handleBackFallback(): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        return handleKeyDown(KeyEvent.KEYCODE_BACK, event)
    }

    fun suspendInput() {
        if (isGameActive()) togglePause() else stopAllScheduledInput()
        gamepadL2TriggerHeld = false
        gamepadR2TriggerHeld = false
        gamepadL2KeyHeld = false
        gamepadR2KeyHeld = false
    }

    private fun startGame(level: Int) {
        selectedLevel = level
        engine = GameEngine(level)
        screen = Screen.GAME
        paused = false
        exitDialog = false
        exitDialogSelection = 0
        stopAllScheduledInput()
        invalidate()
        scheduleGravity()
    }

    private fun goHomeFromGame() {
        stopAllScheduledInput()
        removeCallbacks(gravityRunnable)
        screen = Screen.HOME
        engine = null
        paused = false
        exitDialog = false
        invalidate()
    }

    private fun togglePause() {
        paused = !paused
        stopAllScheduledInput()
        removeCallbacks(gravityRunnable)
        if (!paused) scheduleGravity()
        invalidate()
    }

    private fun openExitDialog() {
        paused = true
        stopAllScheduledInput()
        removeCallbacks(gravityRunnable)
        exitDialog = true
        exitDialogSelection = 0
        invalidate()
    }

    private fun closeExitDialog() {
        exitDialog = false
        // ANNULLA deve tornare direttamente alla partita, anche se l'EXIT è stato
        // aperto mentre il gioco era in pausa.
        paused = false
        scheduleGravity()
        invalidate()
    }

    private fun startSoftDrop() {
        val e = engine ?: return
        softDropHeld = true
        removeCallbacks(softDropRepeatRunnable)
        removeCallbacks(gravityRunnable)
        e.softDropStep()
        invalidate()
        if (!e.gameOver) postDelayed(softDropRepeatRunnable, SOFT_DROP_REPEAT_MS)
    }

    private fun stopSoftDrop() {
        if (!softDropHeld) return
        softDropHeld = false
        removeCallbacks(softDropRepeatRunnable)
        if (isGameActive() && engine?.gameOver == false) scheduleGravity()
    }

    private fun scheduleGravity() {
        removeCallbacks(gravityRunnable)
        val e = engine ?: return
        if (isGameActive() && !softDropHeld && !e.gameOver) {
            postDelayed(gravityRunnable, e.gravityDelayMs().toLong())
        }
    }

    private fun stopAllScheduledInput() {
        softDropHeld = false
        gamepadDownHeld = false
        cancelPendingGamepadHorizontal()
        removeCallbacks(softDropRepeatRunnable)
    }

    private fun isGameActive(): Boolean =
        screen == Screen.GAME && !paused && !exitDialog && engine?.gameOver == false

    private fun isPauseKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_BUTTON_START

    private fun isExitKey(code: Int): Boolean =
        code == TCL_EXIT_KEYCODE || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_SELECT

    private fun lighten(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).toInt().coerceAtMost(255),
        (Color.green(color) * f).toInt().coerceAtMost(255),
        (Color.blue(color) * f).toInt().coerceAtMost(255)
    )

    private fun darken(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).toInt(),
        (Color.green(color) * f).toInt(),
        (Color.blue(color) * f).toInt()
    )
}
