package com.bquelhas.navme

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    // Preset watch background colors (0xRRGGBB). First one is the default red.
    private val palette = intArrayOf(
        0xFF4B49, // vermelho (padrão)
        0x000000, // preto
        0x1565C0, // azul
        0x2E7D32, // verde
        0xEF6C00, // laranja
        0x6A1B9A, // roxo
        0x00897B, // teal
        0xFFFFFF, // branco
    )

    // Runtime location permission request for the GPS speed features.
    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateSpeedControls()
        }

    // Returns from the "display over other apps" settings screen; re-check to hide the button
    // once the user grants the overlay (SYSTEM_ALERT_WINDOW) BAL exemption.
    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateOverlayControls()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }

        findViewById<Button>(R.id.btnDeveloper).setOnClickListener {
            startActivity(Intent(this, DeveloperActivity::class.java))
        }

        val autolaunch = findViewById<SwitchCompat>(R.id.switchAutolaunch)
        autolaunch.isChecked = NavPrefs.isAutolaunch(applicationContext)
        autolaunch.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setAutolaunch(applicationContext, checked)
        }

        val vibe = findViewById<SwitchCompat>(R.id.switchVibe)
        vibe.isChecked = NavPrefs.isVibeOnTurn(applicationContext)
        vibe.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setVibeOnTurn(applicationContext, checked)
            PebbleEmitter.sendVibeOnTurn(applicationContext) // push the new setting now
        }

        setupSpeedControls()
        setupUnitsToggle()
        setupNavAppToggle()

        findViewById<MaterialButton>(R.id.btnGrantOverlay).setOnClickListener {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }

        buildColorPicker()
        applyPreviewColor(NavPrefs.getBgColor(applicationContext))

        updateStatus()
        updateSetupCard()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateSetupCard()
        updateSpeedControls()
        updateOverlayControls()
        // Keep the watch's favorite menu and settings in sync while the app is open.
        if (PebbleEmitter.isWatchConnected(applicationContext)) {
            PebbleEmitter.sendFavorites(applicationContext)
            PebbleEmitter.sendVibeOnTurn(applicationContext)
        }
    }

    private fun setupSpeedControls() {
        val speedometer = findViewById<SwitchCompat>(R.id.switchSpeedometer)
        speedometer.isChecked = NavPrefs.isSpeedometer(applicationContext)
        speedometer.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setSpeedometer(applicationContext, checked)
            if (checked) ensureLocationPermission()
        }

        val alert = findViewById<SwitchCompat>(R.id.switchSpeedAlert)
        alert.isChecked = NavPrefs.isSpeedAlert(applicationContext)
        alert.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setSpeedAlert(applicationContext, checked)
            if (checked) ensureLocationPermission()
        }

        val limitInput = findViewById<TextInputEditText>(R.id.speedLimitInput)
        limitInput.setText(NavPrefs.getSpeedLimit(applicationContext).toString())
        limitInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitSpeedLimit(limitInput)
        }

        findViewById<MaterialButton>(R.id.btnGrantLocation).setOnClickListener {
            ensureLocationPermission()
        }

        updateSpeedControls()
    }

    private fun commitSpeedLimit(input: TextInputEditText) {
        val kmh = input.text?.toString()?.toIntOrNull()
        if (kmh != null && kmh in 1..255) {
            NavPrefs.setSpeedLimit(applicationContext, kmh)
        }
        // Reflect the stored (clamped) value back into the field.
        input.setText(NavPrefs.getSpeedLimit(applicationContext).toString())
    }

    private fun ensureLocationPermission() {
        if (SpeedProvider.hasLocationPermission(applicationContext)) {
            updateSpeedControls()
            return
        }
        locationPermLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** Shows the "grant location" button only when a speed feature is on but permission is missing. */
    private fun updateSpeedControls() {
        val wantsSpeed = NavPrefs.isSpeedometer(applicationContext) || NavPrefs.isSpeedAlert(applicationContext)
        val hasPerm = SpeedProvider.hasLocationPermission(applicationContext)
        findViewById<MaterialButton>(R.id.btnGrantLocation).visibility =
            if (wantsSpeed && !hasPerm) View.VISIBLE else View.GONE
    }

    private fun setupUnitsToggle() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.unitsToggle)
        val checkedId = when (NavPrefs.getUnitSystem(applicationContext)) {
            UnitSystem.AUTO -> R.id.unitAuto
            UnitSystem.METRIC -> R.id.unitMetric
            UnitSystem.IMPERIAL -> R.id.unitImperial
        }
        group.check(checkedId)
        group.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val units = when (buttonId) {
                R.id.unitMetric -> UnitSystem.METRIC
                R.id.unitImperial -> UnitSystem.IMPERIAL
                else -> UnitSystem.AUTO
            }
            NavPrefs.setUnitSystem(applicationContext, units)
        }
    }

    /** Nav-app picker: used when a favorite is started from the watch. Maps NavApp ↔ button ids. */
    private fun setupNavAppToggle() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.navAppToggle)
        val checkedId = when (NavPrefs.getNavApp(applicationContext)) {
            NavApp.AUTO -> R.id.navAppAuto
            NavApp.GOOGLE_MAPS -> R.id.navAppMaps
            NavApp.WAZE -> R.id.navAppWaze
            NavApp.OSMAND -> R.id.navAppOsmand
        }
        group.check(checkedId)
        group.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val app = when (buttonId) {
                R.id.navAppMaps -> NavApp.GOOGLE_MAPS
                R.id.navAppWaze -> NavApp.WAZE
                R.id.navAppOsmand -> NavApp.OSMAND
                else -> NavApp.AUTO
            }
            NavPrefs.setNavApp(applicationContext, app)
        }
    }

    /**
     * Show the "allow opening over other apps" button only until the overlay
     * (SYSTEM_ALERT_WINDOW) permission is granted. With it, a favorite picked on the watch opens
     * the navigator directly; without it the launch degrades to a tap-to-launch notification.
     */
    private fun updateOverlayControls() {
        val granted = Permissions.canDrawOverlays(applicationContext)
        val vis = if (granted) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.btnGrantOverlay).visibility = vis
        findViewById<TextView>(R.id.overlayCaption).visibility = vis
    }

    private fun buildColorPicker() {
        val row = findViewById<LinearLayout>(R.id.colorSwatches)
        row.removeAllViews()
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        val selected = NavPrefs.getBgColor(applicationContext)
        for (rgb in palette) {
            val swatch = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, 0, margin, 0)
            swatch.layoutParams = lp
            swatch.background = swatchDrawable(rgb, rgb == selected)
            swatch.setOnClickListener {
                NavPrefs.setBgColor(applicationContext, rgb)
                applyPreviewColor(rgb)
                buildColorPicker() // refresh selection ring
            }
            row.addView(swatch)
        }
    }

    private fun swatchDrawable(rgb: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF000000.toInt() or rgb)
            val ring = (3 * resources.displayMetrics.density).toInt()
            when {
                selected -> setStroke(ring, 0xFF1565C0.toInt())          // blue selection ring
                rgb == 0xFFFFFF -> setStroke(2, 0xFFBBBBBB.toInt())      // keep white visible
            }
        }

    /** Mirror the watch: black/white text picked by luminance of the background. */
    private fun contrastColor(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b)
        return if (lum >= 128) Color.BLACK else Color.WHITE
    }

    private fun applyPreviewColor(rgb: Int) {
        val argb = 0xFF000000.toInt() or rgb
        findViewById<MaterialCardView>(R.id.previewCard).setCardBackgroundColor(argb)
        val fg = contrastColor(rgb)
        findViewById<TextView>(R.id.previewDistance).setTextColor(fg)
        findViewById<TextView>(R.id.previewInstruction).setTextColor(fg)
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.status).text = getString(
            if (PebbleEmitter.isWatchConnected(applicationContext)) R.string.watch_connected
            else R.string.watch_disconnected
        )
    }

    /** Onboarding: surface the notification-access step until the user grants it. */
    private fun updateSetupCard() {
        val granted = Permissions.isNotificationAccessGranted(applicationContext)
        findViewById<MaterialCardView>(R.id.setupCard).visibility =
            if (granted) View.GONE else View.VISIBLE
    }
}
