package com.bquelhas.steer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Mock-nav debug front-end. Steps through the REAL Google Maps maneuver glyphs
 * (bundled under assets, decoded from the Maps APK) one at a time. For each one it
 * posts a Maps-style notification carrying that authentic glyph and runs the glyph
 * through [ManeuverClassifier] — the same path used for live Maps notifications —
 * then sends the maneuver WE READ to the watch. The verdict line ("lido como …")
 * shows whether the classifier got it right, so Bruno can audit icon reading
 * one by one and compare phone (Maps glyph) vs. watch (our decoded arrow).
 */
class MockNavActivity : AppCompatActivity() {

    private var glyphs: List<String> = emptyList()
    private var idx = 0

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mock_nav)
        supportActionBar?.title = getString(R.string.mock_nav_title)

        maybeRequestNotifPermission()
        glyphs = MapsGlyphs.names(applicationContext)

        val label = findViewById<TextView>(R.id.mockIconLabel)
        val preview = findViewById<ImageView>(R.id.mockIconPreview)
        val verdict = findViewById<TextView>(R.id.mockVerdict)
        findViewById<Button>(R.id.mockIconPrev).setOnClickListener { step(-1, label, preview, verdict) }
        findViewById<Button>(R.id.mockIconNext).setOnClickListener { step(+1, label, preview, verdict) }
        renderGlyph(label, preview)

        findViewById<Button>(R.id.mockSend).setOnClickListener {
            if (glyphs.isEmpty()) return@setOnClickListener
            val name = glyphs[idx]
            val dist = findViewById<TextInputEditText>(R.id.mockDist).text?.toString().orEmpty()
            val street = findViewById<TextInputEditText>(R.id.mockStreet).text?.toString().orEmpty()
            val eta = findViewById<TextInputEditText>(R.id.mockEta).text?.toString().orEmpty()
            val r = MockNavEmitter.sendMapsGlyph(applicationContext, name, dist, street, eta.ifBlank { null })
            verdict.text = if (r != null)
                getString(R.string.mock_read_as, r.direction.name, r.confidence.name)
            else getString(R.string.mock_read_fail)
            Toast.makeText(this, getString(R.string.mock_sent), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.mockCancel).setOnClickListener {
            MockNavEmitter.cancel(applicationContext)
            Toast.makeText(this, getString(R.string.mock_cancelled), Toast.LENGTH_SHORT).show()
        }
    }

    private fun step(delta: Int, label: TextView, preview: ImageView, verdict: TextView) {
        if (glyphs.isEmpty()) return
        val n = glyphs.size
        idx = ((idx + delta) % n + n) % n
        verdict.text = ""
        renderGlyph(label, preview)
    }

    private fun renderGlyph(label: TextView, preview: ImageView) {
        if (glyphs.isEmpty()) {
            label.text = getString(R.string.mock_no_glyphs)
            return
        }
        val name = glyphs[idx]
        label.text = "${idx + 1}/${glyphs.size} · $name"
        val bmp = MapsGlyphs.load(applicationContext, name)
        if (bmp != null) preview.setImageDrawable(BitmapDrawable(resources, bmp))
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
