package com.breathinghand

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.breathinghand.audio.OboeSynthesizer

/**
 * Compact floating controls panel for ADSR + Filter cutoff.
 * Applies edits to all synth channels (0..7).
 */
class ControlsOverlayView(context: Context, private val synth: OboeSynthesizer) : FrameLayout(context) {
    private val panel: LinearLayout
    private val toggle: ImageButton

    // Callback invoked when user wants to load a mapped sample
    var onLoadMappedSample: (() -> Unit)? = null

    // Callback invoked when user wants to import an SFZ file and associated samples
    var onImportSfz: (() -> Unit)? = null

    // Callback invoked when user wants to manage loaded samples
    var onManageSamples: (() -> Unit)? = null

    init {
        // Toggle button
        toggle = ImageButton(context)
        toggle.setImageResource(android.R.drawable.ic_menu_manage)
        toggle.setBackgroundColor(Color.argb(160, 0, 0, 0))
        val tParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        tParams.gravity = Gravity.BOTTOM or Gravity.END
        toggle.layoutParams = tParams
        toggle.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        addView(toggle)

        // Panel
        panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(220, 0, 0, 0))
        val pad = (8 * resources.displayMetrics.density).toInt()
        panel.setPadding(pad, pad, pad, pad)
        val pParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        pParams.gravity = Gravity.BOTTOM or Gravity.END
        panel.layoutParams = pParams

        // Add controls: cutoff, attack, decay, sustain, release
        addControl("Cutoff", 127) { progress ->
            val vNorm = progress / 127.0
            val cutoff = (20.0 * Math.pow(12000.0 / 20.0, vNorm)).toFloat()
            setAllCutoff(cutoff)
        }
        addControl("Attack (ms)", 2000) { progress ->
            val attackMs = progress.coerceAtLeast(1).toFloat()
            setAllEnvelope(attackMs, null, null, null)
        }
        addControl("Decay (ms)", 2000) { progress ->
            val decayMs = progress.coerceAtLeast(1).toFloat()
            setAllEnvelope(null, decayMs, null, null)
        }
        addControl("Sustain (%)", 100) { progress ->
            val sustain = (progress / 100.0f).coerceIn(0f, 1f)
            setAllEnvelope(null, null, sustain, null)
        }
        addControl("Release (ms)", 3000) { progress ->
            val releaseMs = progress.coerceAtLeast(1).toFloat()
            setAllEnvelope(null, null, null, releaseMs)
        }

        // Waveform selector with active indicator
        val waveNames = arrayOf("Sine", "Triangle", "Saw", "Square")
        val waveRow = LinearLayout(context)
        waveRow.orientation = LinearLayout.HORIZONTAL

        // Label showing current waveform
        val waveLabel = TextView(context)
        waveLabel.setTextColor(Color.LTGRAY)
        waveLabel.text = "Waveform: ${waveNames[0]}"
        waveLabel.setPadding(8, 6, 8, 6)
        panel.addView(waveLabel)

        var selectedWaveIndex = 0
        fun setSelectedWave(idx: Int) {
            selectedWaveIndex = idx
            waveLabel.text = "Waveform: ${waveNames[idx]}"
            for (j in 0 until waveRow.childCount) {
                val c = waveRow.getChildAt(j) as TextView
                c.setBackgroundColor(if (j == idx) Color.argb(140, 255, 255, 255) else Color.TRANSPARENT)
            }
        }

        for ((i, name) in waveNames.withIndex()) {
            val btn = TextView(context)
            btn.setTextColor(Color.WHITE)
            btn.text = name
            btn.setPadding(12, 8, 12, 8)
            btn.setOnClickListener {
                setSelectedWave(i)
                synth.setWaveform(i)
            }
            waveRow.addView(btn)
        }
        // Initialize highlight
        setSelectedWave(0)

        val wlp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        wlp.setMargins(0, 8, 0, 8)
        panel.addView(waveRow, wlp)

        // Load mapped sample button
        val loadSampleBtn = TextView(context)
        loadSampleBtn.setTextColor(Color.WHITE)
        loadSampleBtn.text = "Load Sample Map"
        loadSampleBtn.setPadding(12, 8, 12, 8)
        loadSampleBtn.setOnClickListener {
            onLoadMappedSample?.invoke()
        }
        panel.addView(loadSampleBtn, wlp)

        // Import SFZ button
        val importSfzBtn = TextView(context)
        importSfzBtn.setTextColor(Color.WHITE)
        importSfzBtn.text = "Import SFZ"
        importSfzBtn.setPadding(12, 8, 12, 8)
        importSfzBtn.setOnClickListener {
            onImportSfz?.invoke()
        }
        panel.addView(importSfzBtn, wlp)

        // Manage samples button
        val manageBtn = TextView(context)
        manageBtn.setTextColor(Color.WHITE)
        manageBtn.text = "Manage Samples"
        manageBtn.setPadding(12, 8, 12, 8)
        manageBtn.setOnClickListener {
            onManageSamples?.invoke()
        }
        panel.addView(manageBtn, wlp)

        addView(panel)
        panel.visibility = View.GONE
    }

    private fun addControl(labelText: String, max: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        val label = TextView(context)
        label.setTextColor(Color.WHITE)
        label.text = labelText
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val seek = SeekBar(context)
        seek.max = max
        seek.progress = max / 2
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row.addView(label)
        row.addView(seek)
        val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 8, 0, 8)
        panel.addView(row, lp)
    }

    private var envAttack: Float? = null
    private var envDecay: Float? = null
    private var envSustain: Float? = null
    private var envRelease: Float? = null

    private fun setAllCutoff(cutoff: Float) {
        for (i in 0 until 8) synth.setFilterCutoff(i, cutoff)
    }

    private fun setAllEnvelope(attackMs: Float?, decayMs: Float?, sustain: Float?, releaseMs: Float?) {
        if (attackMs != null) envAttack = attackMs
        if (decayMs != null) envDecay = decayMs
        if (sustain != null) envSustain = sustain
        if (releaseMs != null) envRelease = releaseMs
        val a = envAttack ?: 5f
        val d = envDecay ?: 50f
        val s = envSustain ?: 0.8f
        val r = envRelease ?: 100f
        for (i in 0 until 8) synth.setEnvelope(i, a, d, s, r)
    }
}
