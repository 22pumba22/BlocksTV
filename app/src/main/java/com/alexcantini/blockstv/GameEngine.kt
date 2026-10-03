package com.alexcantini.blockstv

import kotlin.math.max
import kotlin.random.Random

class GameEngine(private val startLevel: Int) {
    companion object {
        const val COLS = 10
        const val ROWS = 20

        // Curva TV ribilanciata: 10 livelli, con velocità massima pari al vecchio livello 8 (155 ms).
        val LEVEL_DELAYS_MS = intArrayOf(600, 515, 445, 380, 330, 285, 245, 210, 180, 155)

        // Soglie cumulative recuperate dal binario originale.
        private val LEVEL_LINE_THRESHOLDS = intArrayOf(11, 17, 25, 35, 37, 51, 65, 79, 93)
    }

    data class Cell(val x: Int, val y: Int)
    data class Piece(var type: Int, var rotation: Int = 0, var x: Int = 3, var y: Int = 0)

    val board = Array(ROWS) { IntArray(COLS) }

    var score = 0
        private set
    var lines = 0
        private set
    var level = startLevel.coerceIn(1, 10)
        private set
    var gameOver = false
        private set

    private val colors = intArrayOf(
        0xFF22C7F2.toInt(), // I cyan
        0xFFFFD52A.toInt(), // O yellow
        0xFFB43CFF.toInt(), // T purple
        0xFF42D84C.toInt(), // S green
        0xFFFF3D45.toInt(), // Z red
        0xFF3368FF.toInt(), // J blue
        0xFFFF941E.toInt()  // L orange
    )

    // Ogni rotazione è una lista x,y nel box 4x4.
    private val shapes: Array<Array<Array<Cell>>> = arrayOf(
        arrayOf(
            arrayOf(Cell(0,1), Cell(1,1), Cell(2,1), Cell(3,1)),
            arrayOf(Cell(2,0), Cell(2,1), Cell(2,2), Cell(2,3)),
            arrayOf(Cell(0,2), Cell(1,2), Cell(2,2), Cell(3,2)),
            arrayOf(Cell(1,0), Cell(1,1), Cell(1,2), Cell(1,3))
        ),
        arrayOf(
            arrayOf(Cell(1,0), Cell(2,0), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(2,0), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(2,0), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(2,0), Cell(1,1), Cell(2,1))
        ),
        arrayOf(
            arrayOf(Cell(1,0), Cell(0,1), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(1,1), Cell(2,1), Cell(1,2)),
            arrayOf(Cell(0,1), Cell(1,1), Cell(2,1), Cell(1,2)),
            arrayOf(Cell(1,0), Cell(0,1), Cell(1,1), Cell(1,2))
        ),
        arrayOf(
            arrayOf(Cell(1,0), Cell(2,0), Cell(0,1), Cell(1,1)),
            arrayOf(Cell(1,0), Cell(1,1), Cell(2,1), Cell(2,2)),
            arrayOf(Cell(1,1), Cell(2,1), Cell(0,2), Cell(1,2)),
            arrayOf(Cell(0,0), Cell(0,1), Cell(1,1), Cell(1,2))
        ),
        arrayOf(
            arrayOf(Cell(0,0), Cell(1,0), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(2,0), Cell(1,1), Cell(2,1), Cell(1,2)),
            arrayOf(Cell(0,1), Cell(1,1), Cell(1,2), Cell(2,2)),
            arrayOf(Cell(1,0), Cell(0,1), Cell(1,1), Cell(0,2))
        ),
        arrayOf(
            arrayOf(Cell(0,0), Cell(0,1), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(2,0), Cell(1,1), Cell(1,2)),
            arrayOf(Cell(0,1), Cell(1,1), Cell(2,1), Cell(2,2)),
            arrayOf(Cell(1,0), Cell(1,1), Cell(0,2), Cell(1,2))
        ),
        arrayOf(
            arrayOf(Cell(2,0), Cell(0,1), Cell(1,1), Cell(2,1)),
            arrayOf(Cell(1,0), Cell(1,1), Cell(1,2), Cell(2,2)),
            arrayOf(Cell(0,1), Cell(1,1), Cell(2,1), Cell(0,2)),
            arrayOf(Cell(0,0), Cell(1,0), Cell(1,1), Cell(1,2))
        )
    )

    var current = newRandomPiece()
        private set
    var nextType = Random.nextInt(7)
        private set

    fun colorFor(type: Int): Int = colors[type.coerceIn(0, colors.lastIndex)]
    fun cellsFor(piece: Piece = current): Array<Cell> = shapes[piece.type][piece.rotation and 3]
    fun cellsForType(type: Int, rotation: Int = 0): Array<Cell> = shapes[type][rotation and 3]

    fun gravityDelayMs(): Int = LEVEL_DELAYS_MS[level - 1]

    fun move(dx: Int): Boolean {
        if (gameOver) return false
        val nx = current.x + dx
        if (!collides(nx, current.y, current.rotation)) {
            current.x = nx
            return true
        }
        return false
    }

    fun rotate(): Boolean {
        if (gameOver) return false
        val nr = (current.rotation + 1) and 3
        val kicks = intArrayOf(0, -1, 1, -2, 2)
        for (kick in kicks) {
            if (!collides(current.x + kick, current.y, nr)) {
                current.x += kick
                current.rotation = nr
                return true
            }
        }
        return false
    }

    fun canMoveDown(): Boolean = !collides(current.x, current.y + 1, current.rotation)

    fun stepDown(): Boolean {
        if (gameOver) return false
        if (canMoveDown()) {
            current.y++
            return true
        }
        lockPiece()
        return false
    }

    fun softDropStep(): Boolean {
        val moved = stepDown()
        if (moved) score += 1
        return moved
    }

    private fun lockPiece() {
        for (c in cellsFor()) {
            val bx = current.x + c.x
            val by = current.y + c.y
            if (by in 0 until ROWS && bx in 0 until COLS) {
                board[by][bx] = current.type + 1
            }
        }
        clearLines()
        spawnNext()
    }

    private fun clearLines() {
        var cleared = 0
        var y = ROWS - 1
        while (y >= 0) {
            var full = true
            for (x in 0 until COLS) if (board[y][x] == 0) { full = false; break }
            if (full) {
                for (pull in y downTo 1) board[pull] = board[pull - 1].clone()
                board[0] = IntArray(COLS)
                cleared++
            } else {
                y--
            }
        }

        if (cleared > 0) {
            lines += cleared
            score += when (cleared) {
                1 -> 100
                2 -> 200
                3 -> 400
                else -> 800
            }
            updateLevel()
        }
    }

    private fun updateLevel() {
        var autoLevel = 1
        for (threshold in LEVEL_LINE_THRESHOLDS) {
            if (lines >= threshold) autoLevel++ else break
        }
        level = max(startLevel.coerceIn(1,10), autoLevel.coerceAtMost(10))
    }

    private fun spawnNext() {
        current = Piece(type = nextType)
        nextType = Random.nextInt(7)
        if (collides(current.x, current.y, current.rotation)) gameOver = true
    }

    private fun newRandomPiece(): Piece = Piece(type = Random.nextInt(7))

    private fun collides(px: Int, py: Int, rotation: Int): Boolean {
        for (c in shapes[current.type][rotation and 3]) {
            val x = px + c.x
            val y = py + c.y
            if (x < 0 || x >= COLS || y >= ROWS) return true
            if (y >= 0 && board[y][x] != 0) return true
        }
        return false
    }
}
