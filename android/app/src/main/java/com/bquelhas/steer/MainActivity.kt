package com.bquelhas.steer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.textfield.TextInputEditText

/**
 * Single-activity, three-tab home: Home / Favourites / Developer, driven by a
 * ViewPager2 behind a custom segmented pill control. Home merges the status/preview screen with the
 * customization controls (colour, switches, speed, units, detect-apps). Each tab is a static
 * [PagerAdapter] page; the
 * pager recreates page views, so every view reference is nullable and (re)bound in a bind* function.
 * The activity itself tints from the watch background colour (content-based dynamic colour) so the
 * phone app reads as the same product as the watch.
 */
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

    // --- Page view refs (rebuilt on every page (re)bind; all helpers null-check) ---
    // Home
    private var statusView: TextView? = null
    private var setupCard: MaterialCardView? = null
    private var previewCard: MaterialCardView? = null
    private var previewDistance: TextView? = null
    private var previewInstruction: TextView? = null
    // Customization
    private var colorSwatches: LinearLayout? = null
    private var btnGrantLocation: MaterialButton? = null
    // Favourites
    private var favoritesList: LinearLayout? = null
    private var favEmpty: TextView? = null
    private var btnGrantOverlay: MaterialButton? = null
    private var overlayCaption: TextView? = null
    // Developer
    private var devRoot: View? = null
    private var iconIndex = 0

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateSpeedControls()
        }

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateOverlayControls()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Seed the whole app from the watch's chosen background colour before inflating.
        applyWatchColorTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPager(savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateSetupCard()
        updateSpeedControls()
        updateOverlayControls()
        if (PebbleEmitter.isWatchConnected(applicationContext)) {
            PebbleEmitter.sendFavorites(applicationContext)
            PebbleEmitter.sendVibeOnTurn(applicationContext)
        }
    }

    /**
     * Re-theme the app from [NavPrefs.getBgColor]. On Android 12+ this generates a Material tonal
     * scheme seeded by the colour; on older devices it no-ops and the static red theme stays.
     */
    private fun applyWatchColorTheme() {
        val seed = 0xFF000000.toInt() or NavPrefs.getBgColor(applicationContext)
        DynamicColors.applyToActivityIfAvailable(
            this,
            DynamicColorsOptions.Builder().setContentBasedSource(seed).build()
        )
    }

    // --- ViewPager2 / segmented tabs ---

    private fun setupPager(freshStart: Boolean) {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = PagerAdapter { position, root ->
            when (position) {
                0 -> bindHomePage(root)
                1 -> bindFavouritesPage(root)
                else -> bindDeveloperPage(root)
            }
        }
        // Keep all three pages alive so swiping never loses in-progress edits/state.
        viewPager.offscreenPageLimit = 2

        val segRow = findViewById<LinearLayout>(R.id.segRow)
        val segIndicator = findViewById<View>(R.id.segIndicator)
        val segs = listOf<MaterialButton>(
            findViewById(R.id.segHome),
            findViewById(R.id.segFavs),
            findViewById(R.id.segDev),
        )
        fun selectTab(pos: Int) { segs.forEachIndexed { i, b -> b.isChecked = i == pos } }

        var isProgrammaticClick = false
        fun segWidth() = segRow.width / segs.size
        fun moveIndicator(position: Int, offset: Float) {
            val w = segWidth()
            if (w == 0) return
            if (segIndicator.width != w) {
                segIndicator.layoutParams = segIndicator.layoutParams.apply { width = w }
                segIndicator.requestLayout()
            }
            segIndicator.translationX = (position + offset) * w
        }
        segRow.post { moveIndicator(viewPager.currentItem, 0f); selectTab(viewPager.currentItem) }

        segs.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (viewPager.currentItem != i) {
                    isProgrammaticClick = true
                    viewPager.setCurrentItem(i, true)
                    selectTab(i)
                    val targetX = i * segWidth().toFloat()
                    android.animation.ObjectAnimator.ofFloat(
                        segIndicator, "translationX", segIndicator.translationX, targetX
                    ).apply {
                        duration = 350
                        interpolator = android.view.animation.OvershootInterpolator(1.4f)
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                isProgrammaticClick = false
                            }
                        })
                    }.start()
                }
            }
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = selectTab(position)
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (!isProgrammaticClick) {
                    moveIndicator(position, positionOffset)
                    selectTab(if (positionOffset < 0.5f) position else position + 1)
                }
            }
        })

        if (freshStart) {
            viewPager.setCurrentItem(0, false)
            selectTab(0)
        }
    }

    // --- Home page (status/preview + customization controls) ---

    private fun bindHomePage(root: View) {
        statusView = root.findViewById(R.id.status)
        setupCard = root.findViewById(R.id.setupCard)
        previewCard = root.findViewById(R.id.previewCard)
        previewDistance = root.findViewById(R.id.previewDistance)
        previewInstruction = root.findViewById(R.id.previewInstruction)

        root.findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        applyPreviewColor(NavPrefs.getBgColor(applicationContext))
        updateStatus()
        updateSetupCard()

        // Customization controls now live on the same page.
        bindCustomizationControls(root)
    }

    private fun bindCustomizationControls(root: View) {
        colorSwatches = root.findViewById(R.id.colorSwatches)
        btnGrantLocation = root.findViewById(R.id.btnGrantLocation)
        buildColorPicker()

        val autolaunch = root.findViewById<SwitchCompat>(R.id.switchAutolaunch)
        autolaunch.isChecked = NavPrefs.isAutolaunch(applicationContext)
        autolaunch.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setAutolaunch(applicationContext, checked)
        }

        val vibe = root.findViewById<SwitchCompat>(R.id.switchVibe)
        vibe.isChecked = NavPrefs.isVibeOnTurn(applicationContext)
        vibe.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setVibeOnTurn(applicationContext, checked)
            PebbleEmitter.sendVibeOnTurn(applicationContext)
        }

        setupSpeedControls(root)
        setupUnitsToggle(root)
        setupDetectApps(root)
    }

    private fun setupSpeedControls(root: View) {
        // Speedometer behaviour: Off / Always / Bicycle-only (shows speed only for a watch-launched
        // bicycle route). Replaces the old per-mode checkboxes.
        val speedModeToggle = root.findViewById<MaterialButtonToggleGroup>(R.id.speedModeToggle)
        speedModeToggle.check(
            when (NavPrefs.getSpeedometerMode(applicationContext)) {
                SpeedometerMode.OFF -> R.id.speedModeOff
                SpeedometerMode.ALWAYS -> R.id.speedModeAlways
                SpeedometerMode.BICYCLE -> R.id.speedModeBike
            }
        )
        speedModeToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (buttonId) {
                R.id.speedModeAlways -> SpeedometerMode.ALWAYS
                R.id.speedModeBike -> SpeedometerMode.BICYCLE
                else -> SpeedometerMode.OFF
            }
            NavPrefs.setSpeedometerMode(applicationContext, mode)
            if (mode != SpeedometerMode.OFF) ensureLocationPermission()
            updateSpeedControls()
        }

        val alert = root.findViewById<SwitchCompat>(R.id.switchSpeedAlert)
        alert.isChecked = NavPrefs.isSpeedAlert(applicationContext)
        alert.setOnCheckedChangeListener { _, checked ->
            NavPrefs.setSpeedAlert(applicationContext, checked)
            if (checked) ensureLocationPermission()
        }

        // Limit source: Manual (fixed preset) vs OSM (real road limit, uses data).
        val sourceToggle = root.findViewById<MaterialButtonToggleGroup>(R.id.speedSourceToggle)
        val osmNote = root.findViewById<TextView>(R.id.speedOsmNote)
        sourceToggle.check(
            if (NavPrefs.isOsmSpeedLimit(applicationContext)) R.id.sourceOsm else R.id.sourceManual
        )
        osmNote.visibility = if (NavPrefs.isOsmSpeedLimit(applicationContext)) View.VISIBLE else View.GONE
        sourceToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val osm = buttonId == R.id.sourceOsm
            NavPrefs.setOsmSpeedLimit(applicationContext, osm)
            osmNote.visibility = if (osm) View.VISIBLE else View.GONE
        }

        // Manual limit presets (also the OSM fallback).
        val limitToggle = root.findViewById<MaterialButtonToggleGroup>(R.id.speedLimitToggle)
        limitToggle.check(presetButtonFor(NavPrefs.getSpeedLimit(applicationContext)))
        limitToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            NavPrefs.setSpeedLimit(applicationContext, presetValueFor(buttonId))
        }

        btnGrantLocation?.setOnClickListener { ensureLocationPermission() }
        updateSpeedControls()
    }

    private fun presetButtonFor(kmh: Int): Int = when (kmh) {
        100 -> R.id.limit100
        130 -> R.id.limit130
        else -> R.id.limit120
    }

    private fun presetValueFor(buttonId: Int): Int = when (buttonId) {
        R.id.limit100 -> 100
        R.id.limit130 -> 130
        else -> 120
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

    private fun updateSpeedControls() {
        val wantsSpeed = NavPrefs.isSpeedometerEnabled(applicationContext) || NavPrefs.isSpeedAlert(applicationContext)
        val hasPerm = SpeedProvider.hasLocationPermission(applicationContext)
        btnGrantLocation?.visibility = if (wantsSpeed && !hasPerm) View.VISIBLE else View.GONE
    }

    private fun setupUnitsToggle(root: View) {
        val group = root.findViewById<MaterialButtonToggleGroup>(R.id.unitsToggle)
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

    /**
     * Multiselect of which navigator notifications the listener reads. Each checkbox maps to the
     * package(s) in the persisted detect-apps set; OsmAnd covers both the free and paid packages.
     */
    private fun setupDetectApps(root: View) {
        val boxes = listOf(
            root.findViewById<MaterialCheckBox>(R.id.chkDetectMaps) to setOf(NaviParser.PKG_GOOGLE_MAPS),
            root.findViewById<MaterialCheckBox>(R.id.chkDetectOsmand) to setOf(NaviParser.PKG_OSMAND, NaviParser.PKG_OSMAND_FREE),
            root.findViewById<MaterialCheckBox>(R.id.chkDetectComaps) to setOf(NaviParser.PKG_COMAPS),
            root.findViewById<MaterialCheckBox>(R.id.chkDetectOrganic) to setOf(NaviParser.PKG_ORGANIC),
        )
        val current = NavPrefs.getDetectApps(applicationContext)
        boxes.forEach { (box, pkgs) ->
            box.isChecked = pkgs.any { it in current }
            box.setOnCheckedChangeListener { _, _ ->
                val next = mutableSetOf<String>()
                boxes.forEach { (b, p) -> if (b.isChecked) next += p }
                NavPrefs.setDetectApps(applicationContext, next)
            }
        }
    }

    private fun buildColorPicker() {
        val row = colorSwatches ?: return
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
            swatch.setOnClickListener { onPickWatchColor(rgb) }
            row.addView(swatch)
        }
    }

    /** Persist the new watch background, reflect it in the preview, and re-tint the whole app. */
    private fun onPickWatchColor(rgb: Int) {
        if (rgb == NavPrefs.getBgColor(applicationContext)) return
        NavPrefs.setBgColor(applicationContext, rgb)
        applyPreviewColor(rgb)
        buildColorPicker()
        // Re-seed the app palette from the new colour. recreate() re-runs applyWatchColorTheme();
        // ViewPager2 restores the current tab from instance state, so we stay on Customization.
        recreate()
    }

    private fun swatchDrawable(rgb: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF000000.toInt() or rgb)
            val ring = (3 * resources.displayMetrics.density).toInt()
            when {
                selected -> setStroke(ring, 0xFF1565C0.toInt())
                rgb == 0xFFFFFF -> setStroke(2, 0xFFBBBBBB.toInt())
            }
        }

    private fun contrastColor(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b)
        return if (lum >= 128) Color.BLACK else Color.WHITE
    }

    private fun applyPreviewColor(rgb: Int) {
        val argb = 0xFF000000.toInt() or rgb
        previewCard?.setCardBackgroundColor(argb)
        val fg = contrastColor(rgb)
        previewDistance?.setTextColor(fg)
        previewInstruction?.setTextColor(fg)
    }

    // --- Favourites page ---

    private fun bindFavouritesPage(root: View) {
        favoritesList = root.findViewById(R.id.favoritesList)
        favEmpty = root.findViewById(R.id.favEmpty)
        btnGrantOverlay = root.findViewById(R.id.btnGrantOverlay)
        overlayCaption = root.findViewById(R.id.overlayCaption)

        setupNavAppToggle(root)

        btnGrantOverlay?.setOnClickListener {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
        root.findViewById<MaterialButton>(R.id.btnAddFavorite).setOnClickListener { showFavoriteEditor(null, null) }

        updateOverlayControls()
        renderFavorites()
    }

    /** Nav-app picker: which navigator opens when a favourite is started from the watch. */
    private fun setupNavAppToggle(root: View) {
        val group = root.findViewById<MaterialButtonToggleGroup>(R.id.navAppToggle)
        val checkedId = when (NavPrefs.getNavApp(applicationContext)) {
            NavApp.AUTO -> R.id.navAppAuto
            NavApp.GOOGLE_MAPS -> R.id.navAppMaps
            NavApp.OSMAND -> R.id.navAppOsmand
            NavApp.ORGANIC -> R.id.navAppOrganic
            NavApp.COMAPS -> R.id.navAppComaps
        }
        group.check(checkedId)
        group.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val app = when (buttonId) {
                R.id.navAppMaps -> NavApp.GOOGLE_MAPS
                R.id.navAppOsmand -> NavApp.OSMAND
                R.id.navAppOrganic -> NavApp.ORGANIC
                R.id.navAppComaps -> NavApp.COMAPS
                else -> NavApp.AUTO
            }
            NavPrefs.setNavApp(applicationContext, app)
        }
    }

    private fun renderFavorites() {
        val container = favoritesList ?: return
        container.removeAllViews()
        val favs = FavoritesStore.all(applicationContext)
        favEmpty?.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        favs.forEachIndexed { index, fav ->
            val row = inflater.inflate(R.layout.item_favorite, container, false)
            row.findViewById<ImageView>(R.id.favIcon)
                .setImageResource(FavIcons.drawableRes(applicationContext, fav.icon))
            row.findViewById<TextView>(R.id.favLabel).text = fav.label
            // Tap the row or the pencil to open the editor.
            row.findViewById<View>(R.id.favRow).setOnClickListener { showFavoriteEditor(index, fav) }
            row.findViewById<MaterialButton>(R.id.btnEditFav)
                .setOnClickListener { showFavoriteEditor(index, fav) }
            container.addView(row)
        }

        if (PebbleEmitter.isWatchConnected(applicationContext)) {
            PebbleEmitter.sendFavorites(applicationContext)
        }
    }

    /**
     * Unified add/edit sheet. [index]/[existing] are null when adding. Holds the chosen icon id in a
     * local box so the icon picker can update it, then saves (add or update) and, when editing,
     * offers a Delete via the dialog's neutral button.
     */
    private fun showFavoriteEditor(index: Int?, existing: Favorite?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_favorite, null)
        val nameField = view.findViewById<TextInputEditText>(R.id.favName)
        val addrField = view.findViewById<TextInputEditText>(R.id.favAddress)
        val iconBtn = view.findViewById<MaterialButton>(R.id.favIconPick)

        nameField.setText(existing?.label.orEmpty())
        addrField.setText(existing?.query.orEmpty())

        val iconId = intArrayOf(existing?.icon ?: 0)
        fun refreshIconBtn() {
            iconBtn.icon = androidx.core.content.ContextCompat.getDrawable(
                this, FavIcons.drawableRes(applicationContext, iconId[0])
            )
        }
        refreshIconBtn()
        iconBtn.setOnClickListener {
            showIconPicker(iconId[0]) { picked -> iconId[0] = picked; refreshIconBtn() }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.fav_add else R.string.fav_edit)
            .setView(view)
            .setPositiveButton(R.string.fav_save) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty()
                val addr = addrField.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || addr.isEmpty()) {
                    Toast.makeText(this, R.string.fav_incomplete, Toast.LENGTH_SHORT).show()
                } else {
                    val fav = Favorite(name, addr, iconId[0])
                    if (index == null) FavoritesStore.add(applicationContext, fav)
                    else FavoritesStore.update(applicationContext, index, fav)
                    renderFavorites()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (index != null && existing != null) {
            builder.setNeutralButton(R.string.fav_delete) { _, _ -> confirmDeleteFavorite(index, existing) }
        }
        builder.show()
    }

    /** Full-grid icon chooser (all 106 favourite glyphs); reports the tapped id back via [onPick]. */
    private fun showIconPicker(currentId: Int, onPick: (Int) -> Unit) {
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 5)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.fav_icon_pick)
            .setView(rv)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        rv.adapter = IconPickerAdapter(this, currentId) { picked ->
            onPick(picked)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun confirmDeleteFavorite(index: Int, fav: Favorite) {
        AlertDialog.Builder(this)
            .setTitle(fav.label)
            .setMessage(R.string.fav_delete_confirm)
            .setPositiveButton(R.string.fav_delete) { _, _ ->
                FavoritesStore.removeAt(applicationContext, index)
                renderFavorites()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateOverlayControls() {
        val granted = Permissions.canDrawOverlays(applicationContext)
        val vis = if (granted) View.GONE else View.VISIBLE
        btnGrantOverlay?.visibility = vis
        overlayCaption?.visibility = vis
    }

    // --- Developer page ---

    private fun bindDeveloperPage(root: View) {
        devRoot = root

        val debugSwitch = root.findViewById<SwitchCompat>(R.id.switchDebugTests)
        debugSwitch.isChecked = NavPrefs.isDebugTests(applicationContext)
        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            NavPrefs.setDebugTests(applicationContext, isChecked)
            if (!isChecked) {
                DebugCycler.stop(applicationContext)
                MockNavEmitter.cancel(applicationContext)
                clearSpeedSignTest()
                updateDebugCycleButton()
            }
            setDebugControlsEnabled(isChecked)
        }

        root.findViewById<Button>(R.id.btnMock).setOnClickListener {
            val mock = NaviData(Direction.LEFT, "300 m — Turn left onto Rua da Paz", "Good")
            PebbleEmitter.sendNav(applicationContext, mock)
            Toast.makeText(this, getString(R.string.mock_sent), Toast.LENGTH_SHORT).show()
        }
        root.findViewById<Button>(R.id.btnMockNav).setOnClickListener {
            startActivity(Intent(this, MockNavActivity::class.java))
        }
        val btnSign = root.findViewById<Button>(R.id.btnTestSpeedSign)
        btnSign.setOnClickListener { toggleSpeedSignTest(btnSign) }
        updateSpeedSignButton(btnSign)
        root.findViewById<Button>(R.id.btnInstallPbw).setOnClickListener {
            when {
                !PbwInstaller.isBundled(applicationContext) ->
                    Toast.makeText(this, R.string.pbw_install_missing, Toast.LENGTH_LONG).show()
                !PbwInstaller.install(this) ->
                    Toast.makeText(this, R.string.pbw_install_none, Toast.LENGTH_LONG).show()
            }
        }
        val btnDebug = root.findViewById<Button>(R.id.btnDebugCycle)
        btnDebug.setOnClickListener {
            if (DebugCycler.isRunning()) DebugCycler.stop(applicationContext)
            else DebugCycler.start(applicationContext)
            updateDebugCycleButton()
        }
        updateDebugCycleButton()

        val iconLabel = root.findViewById<TextView>(R.id.iconLabel)
        val iconPreview = root.findViewById<ImageView>(R.id.iconPreview)
        root.findViewById<Button>(R.id.btnIconPrev).setOnClickListener { stepIcon(-1, iconLabel, iconPreview) }
        root.findViewById<Button>(R.id.btnIconNext).setOnClickListener { stepIcon(+1, iconLabel, iconPreview) }
        renderIcon(iconLabel, iconPreview)

        val debugOn = NavPrefs.isDebugTests(applicationContext)
        setDebugControlsEnabled(debugOn)
        if (!debugOn) MockNavEmitter.cancel(applicationContext)
    }

    private fun setDebugControlsEnabled(enabled: Boolean) {
        val root = devRoot ?: return
        for (id in intArrayOf(
            R.id.btnMock, R.id.btnDebugCycle, R.id.btnMockNav,
            R.id.btnTestSpeedSign, R.id.btnIconPrev, R.id.btnIconNext,
        )) {
            root.findViewById<Button>(id).isEnabled = enabled
        }
    }

    private fun updateDebugCycleButton() {
        val btn = devRoot?.findViewById<Button>(R.id.btnDebugCycle) ?: return
        btn.text = getString(
            if (DebugCycler.isRunning()) R.string.debug_cycle_stop
            else R.string.debug_cycle_start
        )
    }

    private var speedSignTestOn = false

    private fun toggleSpeedSignTest(btn: Button) {
        speedSignTestOn = !speedSignTestOn
        PebbleEmitter.sendSpeedAlert(
            applicationContext,
            speedSignTestOn,
            NavPrefs.getSpeedLimit(applicationContext),
        )
        updateSpeedSignButton(btn)
    }

    private fun updateSpeedSignButton(btn: Button) {
        btn.text = getString(
            if (speedSignTestOn) R.string.clear_speed_sign
            else R.string.test_speed_sign
        )
    }

    private fun clearSpeedSignTest() {
        if (!speedSignTestOn) return
        speedSignTestOn = false
        PebbleEmitter.sendSpeedAlert(
            applicationContext,
            false,
            NavPrefs.getSpeedLimit(applicationContext),
        )
        devRoot?.findViewById<Button>(R.id.btnTestSpeedSign)?.let { updateSpeedSignButton(it) }
    }

    private fun stepIcon(delta: Int, label: TextView, preview: ImageView) {
        if (DebugCycler.isRunning()) {
            DebugCycler.stop(applicationContext)
            updateDebugCycleButton()
        }
        val n = Direction.entries.size
        iconIndex = ((iconIndex + delta) % n + n) % n
        renderIcon(label, preview)
        val dir = Direction.entries[iconIndex]
        val pretty = dir.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        PebbleEmitter.sendNav(
            applicationContext,
            NaviData(dir, "200 m — $pretty • Teste", gpsAccuracy = "Good", eta = "12:00"),
        )
    }

    private fun renderIcon(label: TextView, preview: ImageView) {
        val dir = Direction.entries[iconIndex]
        label.text = "${dir.id} · ${dir.name}"
        preview.setImageResource(dir.iconRes)
    }

    // --- Shared status helpers (null-safe; called from onResume before pages exist) ---

    private fun updateStatus() {
        statusView?.text = getString(
            if (PebbleEmitter.isWatchConnected(applicationContext)) R.string.watch_connected
            else R.string.watch_disconnected
        )
    }

    private fun updateSetupCard() {
        val granted = Permissions.isNotificationAccessGranted(applicationContext)
        setupCard?.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
