

@file:Suppress("NOTHING_TO_INLINE")

package com.breathinghand.core
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.pow

/**
 * SmartInputHAL
 *
 * Zero-allocation Hardware Abstraction Layer for "Musical Force" (0..1).
 *
 * Goals:
 * - Normalize Android fragmentation (Pressure vs Size) into a stable force signal
 * - Calibrate sensor mode via variance (online, no buffers)
 * - Smooth jitter without killing attack (bypass smoothing on DOWN)
 * - Provide "wack" (percussive slap) detection using size spike + motion spike
 *
 * Designed to integrate with a strict-slotting pointer system (e.g., max 5 fingers).
 *
 * Usage (typical):
 *  - On each MotionEvent: hal.ingest(event)
 *  - Then your engine reads hal.activeCount, and per-slot arrays:
 *      hal.slotPointerId[s], hal.x[s], hal.y[s], hal.force[s], hal.flags[s]
 *
 * Note: This class does NOT allocate during ingest().
 */
class SmartInputHAL(
    private val maxSlots: Int = 5,
    private val cfg: Config = Config()
) {
    // --- Public output arrays (read-only by convention) ---
    val slotPointerId = IntArray(maxSlots) { INVALID_ID }     // pointerId for each slot, or -1
    val x = FloatArray(maxSlots)
    val y = FloatArray(maxSlots)
    val force = FloatArray(maxSlots)                          // 0..1
    val rawPressure = FloatArray(maxSlots)
    val rawSize = FloatArray(maxSlots)
    val flags = IntArray(maxSlots)                            // bitmask
    var activeCount: Int = 0
        private set

    // Expansion metric input (optional): set from your Spatial engine each frame (0..1).
    // If you don’t have it yet, leave as 0f (no compensation).
    var expansion01: Float = 0f

    // --- Modes ---
    enum class SensorMode { CALIBRATING, USE_PRESSURE, USE_SIZE, FALLBACK }
    var mode: SensorMode = SensorMode.CALIBRATING
        private set

    // --- Private per-slot state ---
    private val smoothedForce = FloatArray(maxSlots)
    private val lastX = FloatArray(maxSlots)
    private val lastY = FloatArray(maxSlots)
    private val lastTms = LongArray(maxSlots)
    private val lastSize = FloatArray(maxSlots)
    private val lastDownTms = LongArray(maxSlots)

    // --- Calibration (online variance via Welford) ---
    private var calStartedTms: Long = 0L
    private var calCount: Int = 0
    private val pStats = OnlineVariance()
    private val sStats = OnlineVariance()
    private var calPointerId: Int = INVALID_ID

    // --- Action scratch (no allocations) ---
    private var lastActionMasked: Int = -1
    private var lastActionIndex: Int = -1

    data class Config(
        // Calibration window
        val calibrationMinSamples: Int = 40,
        val calibrationMaxSamples: Int = 90,
        val calibrationMaxMillis: Long = 900L,

        // Mode selection thresholds (variance)
        val pressureVarThreshold: Float = 0.0010f,
        val sizeVarThreshold: Float = 0.0010f,

        // Smoothing: y += alpha * (x - y)
        // alpha closer to 1.0 = less smoothing (more raw), closer to 0 = more smoothing (lag)
        val alphaMove: Float = 0.55f,

        // Size->force mapping range (tune per device family)
        val sizeMin: Float = 0.04f,
        val sizeMax: Float = 0.25f,
        val sizeCurvePow: Float = 2.0f,

        // Pressure normalization (many devices already 0..1-ish, but some are tiny)
        // We map: pressureNorm = clamp((p - pMin) / (pMax - pMin), 0..1)
        val pressureMin: Float = 0.02f,
        val pressureMax: Float = 0.60f,
        val pressureCurvePow: Float = 1.0f,

        // Wack detection
        // Wack if: size spike AND motion spike within early-down window
        val wackEarlyWindowMs: Long = 35L,
        val wackSizeSpike: Float = 0.08f,          // rawSize delta vs lastSize
        val wackSizeAbsolute: Float = 0.22f,       // rawSize must be at least this
        val wackMotionPx: Float = 18f,             // move between samples (px) (device dependent!)
        val wackForceMin: Float = 0.25f,           // avoid wack on feather taps

        // Expansion compensation (optional)
        // corrected = force * (1 + k * expansion^gamma)
        val expansionCompK: Float = 0.18f,
        val expansionCompGamma: Float = 1.6f
    )

    companion object {
        const val INVALID_ID = -1

        // Flags
        const val F_DOWN = 1 shl 0
        const val F_UP = 1 shl 1
        const val F_WACK = 1 shl 2
        const val F_PRIMARY = 1 shl 3

        private inline fun clamp01(v: Float): Float = when {
            v < 0f -> 0f
            v > 1f -> 1f
            else -> v
        }
    }

    /**
     * Main entry: ingest raw MotionEvent, update per-slot arrays.
     * Returns activeCount for convenience.
     *
     * Zero allocations.
     */
    fun ingest(ev: MotionEvent): Int {
        lastActionMasked = ev.actionMasked
        lastActionIndex = ev.actionIndex

        // Clear per-frame flags
        for (s in 0 until maxSlots) flags[s] = 0

        // Calibration step (cheap) using primary pointer only
        if (mode == SensorMode.CALIBRATING) {
            runCalibration(ev)
        }

        // Update all pointers present in this event
        activeCount = 0
        val now = SystemClock.uptimeMillis()

        for (i in 0 until ev.pointerCount) {
            val pid = ev.getPointerId(i)
            val slot = ensureSlot(pid) ?: continue

            val px = ev.getX(i)
            val py = ev.getY(i)
            val p = ev.getPressure(i)
            val s = ev.getSize(i)

            x[slot] = px
            y[slot] = py
            rawPressure[slot] = p
            rawSize[slot] = s

            val isDownForThisPointer =
                (lastActionMasked == MotionEvent.ACTION_DOWN && i == 0) ||
                        (lastActionMasked == MotionEvent.ACTION_POINTER_DOWN && i == lastActionIndex)

            val isUpForThisPointer =
                (lastActionMasked == MotionEvent.ACTION_UP && i == 0) ||
                        (lastActionMasked == MotionEvent.ACTION_POINTER_UP && i == lastActionIndex) ||
                        (lastActionMasked == MotionEvent.ACTION_CANCEL)

            if (isDownForThisPointer) {
                flags[slot] = flags[slot] or F_DOWN
                lastDownTms[slot] = now
                // reset smoothing memory for fast attack
                smoothedForce[slot] = 0f
                lastX[slot] = px
                lastY[slot] = py
                lastTms[slot] = now
                lastSize[slot] = s
            }
            if (isUpForThisPointer) {
                flags[slot] = flags[slot] or F_UP
            }

            // Compute raw force (0..1) by mode
            val rawF = when (mode) {
                SensorMode.USE_PRESSURE -> mapPressureToForce(p)
                SensorMode.USE_SIZE -> mapSizeToForce(s)
                SensorMode.FALLBACK -> fallbackForce(p, s, slot, now) // still uses size/pressure + motion
                SensorMode.CALIBRATING -> mapSizeToForce(s) // default behavior during calibration
            }

            // Optional: compensate for expansion-induced contact changes
            val compF = applyExpansionCompensation(rawF)

            // Smooth jitter except on DOWN (instant attack)
            val cleanF = if (isDownForThisPointer) {
                compF
            } else {
                // LPF: y += alpha * (x - y)
                val prev = smoothedForce[slot]
                val next = prev + cfg.alphaMove * (compF - prev)
                next
            }

            smoothedForce[slot] = cleanF
            force[slot] = clamp01(cleanF)

            // Wack detection: within early window after down, if size spike + motion spike
            if (!isUpForThisPointer) {
                if (detectWack(slot, now, px, py, s, force[slot])) {
                    flags[slot] = flags[slot] or F_WACK
                }
            }

            // Primary flag (slot 0 is often “primary” in strict slotting, but don’t assume)
            if (slot == 0) flags[slot] = flags[slot] or F_PRIMARY

            // Update motion trackers
            lastX[slot] = px
            lastY[slot] = py
            lastSize[slot] = s
            lastTms[slot] = now

            activeCount++
        }

        // Handle pointer-up cleanup (remove only the lifted pointer)
        if (lastActionMasked == MotionEvent.ACTION_UP ||
            lastActionMasked == MotionEvent.ACTION_POINTER_UP ||
            lastActionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val pid = if (lastActionMasked == MotionEvent.ACTION_CANCEL) INVALID_ID else ev.getPointerId(lastActionIndex)
            if (pid != INVALID_ID) releasePointer(pid)
            if (lastActionMasked == MotionEvent.ACTION_CANCEL) resetAll()
        }

        return activeCount
    }

    /** Call if you want to hard reset calibration + slots (e.g., from a debug button). */
    fun resetAll() {
        for (s in 0 until maxSlots) {
            slotPointerId[s] = INVALID_ID
            x[s] = 0f; y[s] = 0f
            force[s] = 0f
            rawPressure[s] = 0f
            rawSize[s] = 0f
            smoothedForce[s] = 0f
            lastX[s] = 0f; lastY[s] = 0f
            lastTms[s] = 0L
            lastSize[s] = 0f
            lastDownTms[s] = 0L
            flags[s] = 0
        }
        activeCount = 0
        mode = SensorMode.CALIBRATING
        calStartedTms = 0L
        calCount = 0
        pStats.reset()
        sStats.reset()
        calPointerId = INVALID_ID
    }

    // -----------------------
    // Calibration
    // -----------------------

    private fun runCalibration(ev: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        if (calStartedTms == 0L) calStartedTms = now

        // Choose calibration pointer: first DOWN pointer id
        if (calPointerId == INVALID_ID) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                calPointerId = ev.getPointerId(0)
            } else {
                // If we missed ACTION_DOWN, just use pointer 0 for now until we see down
                calPointerId = ev.getPointerId(0)
            }
        }

        // Locate calibration pointer index
        val idx = findPointerIndex(ev, calPointerId)
        if (idx < 0) return

        // Sample
        val p = ev.getPressure(idx)
        val s = ev.getSize(idx)
        pStats.push(p.toDouble())
        sStats.push(s.toDouble())
        calCount++

        val elapsed = now - calStartedTms
        val shouldFinalize =
            (calCount >= cfg.calibrationMinSamples && elapsed >= cfg.calibrationMaxMillis / 2) ||
                    (calCount >= cfg.calibrationMaxSamples) ||
                    (elapsed >= cfg.calibrationMaxMillis)

        if (shouldFinalize) finalizeCalibration()
    }

    private fun finalizeCalibration() {
        val pVar = pStats.variance().toFloat()
        val sVar = sStats.variance().toFloat()

        mode = when {
            pVar > cfg.pressureVarThreshold -> SensorMode.USE_PRESSURE
            sVar > cfg.sizeVarThreshold -> SensorMode.USE_SIZE
            else -> SensorMode.FALLBACK
        }

        // Reset calibration counters but keep mode
        calStartedTms = 0L
        calCount = 0
        pStats.reset()
        sStats.reset()
        calPointerId = INVALID_ID

        // You probably want Android Log.d in your app; println is OK for quick debugging.
        println("SmartInputHAL: Mode=$mode (pVar=$pVar, sVar=$sVar)")
    }

    // -----------------------
    // Slot management (strict slotting)
    // -----------------------

    private fun ensureSlot(pointerId: Int): Int? {
        // Already assigned?
        for (s in 0 until maxSlots) {
            if (slotPointerId[s] == pointerId) return s
        }
        // Free slot?
        for (s in 0 until maxSlots) {
            if (slotPointerId[s] == INVALID_ID) {
                slotPointerId[s] = pointerId
                return s
            }
        }
        return null // no slot available
    }

    private fun releasePointer(pointerId: Int) {
        for (s in 0 until maxSlots) {
            if (slotPointerId[s] == pointerId) {
                slotPointerId[s] = INVALID_ID
                force[s] = 0f
                smoothedForce[s] = 0f
                rawPressure[s] = 0f
                rawSize[s] = 0f
                flags[s] = 0
                return
            }
        }
    }

    private fun findPointerIndex(ev: MotionEvent, pointerId: Int): Int {
        for (i in 0 until ev.pointerCount) {
            if (ev.getPointerId(i) == pointerId) return i
        }
        return -1
    }

    // -----------------------
    // Force mapping
    // -----------------------

    private fun mapSizeToForce(rawS: Float): Float {
        // Normalize to 0..1 within [sizeMin, sizeMax]
        val n = (rawS - cfg.sizeMin) / (cfg.sizeMax - cfg.sizeMin)
        val clamped = clamp01(n)
        val pow = cfg.sizeCurvePow
        return if (pow == 1f) clamped else clamped.pow(pow)
    }

    private fun mapPressureToForce(rawP: Float): Float {
        val n = (rawP - cfg.pressureMin) / (cfg.pressureMax - cfg.pressureMin)
        val clamped = clamp01(n)
        val pow = cfg.pressureCurvePow
        return if (pow == 1f) clamped else clamped.pow(pow)
    }

    private fun fallbackForce(rawP: Float, rawS: Float, slot: Int, now: Long): Float {
        // Conservative fallback: combine best guesses
        // - size mapping provides "squish"
        // - pressure mapping may still have tiny useful variation
        // - motion adds “impact” for percussive gestures
        val sizeF = mapSizeToForce(rawS)
        val pressF = mapPressureToForce(rawP)

        val dt = (now - lastTms[slot]).coerceAtLeast(1L).toFloat()
        val dx = x[slot] - lastX[slot]
        val dy = y[slot] - lastY[slot]
        val motion = (abs(dx) + abs(dy)) / dt // px/ms (Manhattan, cheap)
        val impact = clamp01(motion * 0.9f)   // tune factor if needed

        // Weighted blend, biased towards size
        return clamp01(sizeF * 0.70f + pressF * 0.20f + impact * 0.10f)
    }

    private fun applyExpansionCompensation(f: Float): Float {
        val e = clamp01(expansion01)
        if (e <= 0f) return f
        val gain = 1f + cfg.expansionCompK * e.pow(cfg.expansionCompGamma)
        return clamp01(f * gain)
    }

    // -----------------------
    // Wack detection
    // -----------------------

    private fun detectWack(slot: Int, now: Long, px: Float, py: Float, sizeNow: Float, forceNow: Float): Boolean {
        val sinceDown = now - lastDownTms[slot]
        if (sinceDown < 0L || sinceDown > cfg.wackEarlyWindowMs) return false

        val sizeDelta = sizeNow - lastSize[slot]
        if (sizeNow < cfg.wackSizeAbsolute) return false
        if (sizeDelta < cfg.wackSizeSpike) return false
        if (forceNow < cfg.wackForceMin) return false

        // Motion spike: distance since last sample
        val dx = px - lastX[slot]
        val dy = py - lastY[slot]
        val manhattan = abs(dx) + abs(dy)
        return manhattan >= cfg.wackMotionPx
    }

    // -----------------------
    // Online variance (Welford)
    // -----------------------

    private class OnlineVariance {
        private var n = 0
        private var mean = 0.0
        private var m2 = 0.0

        fun reset() {
            n = 0
            mean = 0.0
            m2 = 0.0
        }

        fun push(x: Double) {
            n++
            val delta = x - mean
            mean += delta / n
            val delta2 = x - mean
            m2 += delta * delta2
        }

        fun variance(): Double {
            return if (n < 2) 0.0 else (m2 / (n - 1))
        }
    }
}
