package com.breathinghand.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.*
import android.widget.FrameLayout
import kotlinx.coroutines.*
import java.io.File

/**
 * Debug overlay for telemetry calibration and recording.
 *
 * Provides:
 * - Label selection (FAN, STRETCH, CLUSTER)
 * - Record toggle
 * - Save/Export button
 * - Real-time feedback (fingerCount, archetype)
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var harmonicEngine: HarmonicEngine? = null

    private lateinit var labelSpinner: Spinner
    private lateinit var recordToggle: Switch
    private lateinit var exportButton: Button
    private lateinit var statusText: TextView
    private lateinit var feedbackText: TextView

    private var currentTakeId = 1
    private var exportJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setupUI()
    }

    /**
     * Initialize with engine reference for real-time feedback.
     */
    fun initialize(engine: HarmonicEngine) {
        this.harmonicEngine = engine
    }

    private fun setupUI() {
        val padding = (16 * resources.displayMetrics.density).toInt()

        // Container for controls
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.argb(200, 0, 0, 0)) // Semi-transparent black
        }

        // Label Spinner
        val labelLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val labelLabel = TextView(context).apply {
            text = "Label:"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, padding / 2, 0)
            }
        }
        labelSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                arrayOf("FAN", "STRETCH", "CLUSTER")
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        labelLayout.addView(labelLabel)
        labelLayout.addView(labelSpinner)

        // Record Toggle
        recordToggle = Switch(context).apply {
            text = "Record"
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnCheckedChangeListener { _, isChecked ->
                onRecordToggleChanged(isChecked)
            }
        }

        // Export Button
        exportButton = Button(context).apply {
            text = "Export"
            isEnabled = false
            setOnClickListener {
                onExportClicked()
            }
        }

        // Status Text
        statusText = TextView(context).apply {
            text = "Status: Stopped"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        // Feedback Text (real-time)
        feedbackText = TextView(context).apply {
            text = "Fingers: 0 | Archetype: NONE"
            setTextColor(Color.YELLOW)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }

        container.addView(labelLayout)
        container.addView(recordToggle)
        container.addView(exportButton)
        container.addView(statusText)
        container.addView(feedbackText)

        addView(container)
    }

    private fun onRecordToggleChanged(isChecked: Boolean) {
        if (isChecked) {
            val label = labelSpinner.selectedItem as String
            TelemetryRecorder.startRecording(label, currentTakeId)
            statusText.text = "Status: Recording (Take $currentTakeId)"
            exportButton.isEnabled = false
        } else {
            TelemetryRecorder.stopRecording(getOutputDir())
            statusText.text = "Status: Stopped (Take $currentTakeId)"
            exportButton.isEnabled = true
            currentTakeId++
        }
    }

    private fun onExportClicked() {
        if (exportJob?.isActive == true) return

        exportJob = mainScope.launch {
            statusText.text = "Status: Exporting..."
            exportButton.isEnabled = false

            val deferred = TelemetryRecorder.stopRecording(getOutputDir())
            val file = deferred.await()

            withContext(Dispatchers.Main) {
                if (file != null) {
                    statusText.text = "Status: Exported to ${file.name}"
                    android.widget.Toast.makeText(
                        context,
                        "Exported to ${file.absolutePath}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    statusText.text = "Status: Export failed"
                    android.widget.Toast.makeText(
                        context,
                        "Export failed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                exportButton.isEnabled = true
            }
        }
    }

    /**
     * Update real-time feedback (call from main loop).
     * This is allocation-free (just string building, but we accept it for UI feedback).
     */
    fun updateFeedback() {
        val engine = harmonicEngine ?: return

        val state = engine.state
        val fingerCount = state.fingerCount
        val triad = state.triad
        val seventh = state.seventh

        val archetypeStr = when {
            seventh != 0 -> when (seventh) {
                GestureAnalyzerV01.SEVENTH_COMPACT -> "COMPACT"
                GestureAnalyzerV01.SEVENTH_WIDE -> "WIDE"
                else -> "UNKNOWN"
            }
            triad != 0 -> when (triad) {
                GestureAnalyzerV01.TRIAD_FAN -> "FAN"
                GestureAnalyzerV01.TRIAD_STRETCH -> "STRETCH"
                GestureAnalyzerV01.TRIAD_CLUSTER -> "CLUSTER"
                else -> "UNKNOWN"
            }
            else -> "NONE"
        }

        val (touchDropped, snapDropped) = if (TelemetryRecorder.isRecording()) {
            TelemetryRecorder.getDropCounts()
        } else {
            Pair(0, 0)
        }

        val (touchLevel, snapLevel) = if (TelemetryRecorder.isRecording()) {
            TelemetryRecorder.getBufferLevels()
        } else {
            Pair(0, 0)
        }

        val dropInfo = if (touchDropped > 0 || snapDropped > 0) {
            " | Drops: T=$touchDropped S=$snapDropped"
        } else {
            ""
        }

        feedbackText.text = "Fingers: $fingerCount | Archetype: $archetypeStr | Buffers: T=$touchLevel S=$snapLevel$dropInfo"
    }

    private fun getOutputDir(): File {
        return File(context.getExternalFilesDir(null), "telemetry")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        exportJob?.cancel()
        mainScope.cancel()
    }
}
