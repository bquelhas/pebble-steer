package com.bquelhas.navme

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Página de programador: ferramentas de debug separadas da UI principal —
 * enviar navegação de teste, circular ícones automaticamente e testar cada
 * ícone de manobra um a um. Mantida fora do [MainActivity] para distinguir o
 * uso normal do uso de desenvolvimento.
 */
class DeveloperActivity : AppCompatActivity() {

    private var iconIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer)
        supportActionBar?.title = getString(R.string.developer_title)

        val debugSwitch = findViewById<MaterialSwitch>(R.id.switchDebugTests)
        debugSwitch.isChecked = NavPrefs.isDebugTests(applicationContext)
        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            NavPrefs.setDebugTests(applicationContext, isChecked)
            if (!isChecked) {
                // Turning debug OFF must actively clean up: stop the cycler and clear
                // the lingering mock notification + tell the watch the route ended.
                DebugCycler.stop(applicationContext)
                MockNavEmitter.cancel(applicationContext)
                updateDebugButton(findViewById(R.id.btnDebugCycle))
            }
            setDebugControlsEnabled(isChecked)
        }

        findViewById<Button>(R.id.btnMock).setOnClickListener {
            val mock = NaviData(Direction.LEFT, "300 m — Turn left onto Rua da Paz", "Good")
            PebbleEmitter.sendNav(applicationContext, mock)
            Toast.makeText(this, getString(R.string.mock_sent), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnMockNav).setOnClickListener {
            startActivity(Intent(this, MockNavActivity::class.java))
        }

        findViewById<Button>(R.id.btnInstallPbw).setOnClickListener {
            when {
                !PbwInstaller.isBundled(applicationContext) ->
                    Toast.makeText(this, R.string.pbw_install_missing, Toast.LENGTH_LONG).show()
                !PbwInstaller.install(this) ->
                    Toast.makeText(this, R.string.pbw_install_none, Toast.LENGTH_LONG).show()
            }
        }

        val btnDebug = findViewById<Button>(R.id.btnDebugCycle)
        btnDebug.setOnClickListener {
            if (DebugCycler.isRunning()) DebugCycler.stop(applicationContext)
            else DebugCycler.start(applicationContext)
            updateDebugButton(btnDebug)
        }
        updateDebugButton(btnDebug)

        val iconLabel = findViewById<TextView>(R.id.iconLabel)
        val iconPreview = findViewById<ImageView>(R.id.iconPreview)
        findViewById<Button>(R.id.btnIconPrev).setOnClickListener { stepIcon(-1, iconLabel, iconPreview) }
        findViewById<Button>(R.id.btnIconNext).setOnClickListener { stepIcon(+1, iconLabel, iconPreview) }
        renderIcon(iconLabel, iconPreview)

        val debugOn = NavPrefs.isDebugTests(applicationContext)
        setDebugControlsEnabled(debugOn)
        // If debug is off, make sure no stale mock notification is lingering (e.g. one
        // left over from a previous build / before this switch existed).
        if (!debugOn) MockNavEmitter.cancel(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        updateDebugButton(findViewById(R.id.btnDebugCycle))
    }

    /** Greys out every debug tool when the master switch is off, so nothing can fire. */
    private fun setDebugControlsEnabled(enabled: Boolean) {
        for (id in intArrayOf(
            R.id.btnMock, R.id.btnDebugCycle, R.id.btnMockNav,
            R.id.btnIconPrev, R.id.btnIconNext,
        )) {
            findViewById<Button>(id).isEnabled = enabled
        }
    }

    private fun updateDebugButton(btn: Button) {
        btn.text = getString(
            if (DebugCycler.isRunning()) R.string.debug_cycle_stop
            else R.string.debug_cycle_start
        )
    }

    /** Steps to the next/previous maneuver icon and pushes it to the watch. */
    private fun stepIcon(delta: Int, label: TextView, preview: ImageView) {
        if (DebugCycler.isRunning()) {
            DebugCycler.stop(applicationContext)
            updateDebugButton(findViewById(R.id.btnDebugCycle))
        }
        val n = Direction.entries.size
        iconIndex = ((iconIndex + delta) % n + n) % n
        renderIcon(label, preview)
        val dir = Direction.entries[iconIndex]
        val pretty = dir.name.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
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
}
