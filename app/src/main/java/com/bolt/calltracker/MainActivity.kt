package com.bolt.calltracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private companion object {
        const val REQ_CALL_LOG = 1
        const val REQ_BATTERY = 2
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private var nameInput: EditText? = null
    private var teamGroup: RadioGroup? = null
    private var urlInput: EditText? = null
    private var secretInput: EditText? = null
    private val team: List<String> = Config.TEAM.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Sync.PREFS, Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val saved = prefs.getString("employee", "").orEmpty()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Bolt Call Tracker"
        title.textSize = 24f
        root.addView(title)

        val info = TextView(this)
        info.text = "Sends this phone's call history (number, time, duration and call type) to your company sheet. " +
            "No call audio is recorded.\n\nTap the button below and allow everything it asks for."
        info.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(info)

        root.addView(makeLabel(if (team.isNotEmpty()) "Who are you?" else "Your name (same as in the CRM)"))
        if (team.isNotEmpty()) {
            val group = RadioGroup(this)
            for (n in team) {
                val rb = RadioButton(this)
                rb.id = View.generateViewId()
                rb.text = n
                rb.isChecked = (n == saved)
                group.addView(rb)
            }
            teamGroup = group
            root.addView(group)
        } else {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            input.setText(saved)
            nameInput = input
            root.addView(input)
        }

        if (Config.URL.isBlank()) {
            root.addView(makeLabel("Sheet link (Web app URL)"))
            val u = EditText(this)
            u.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            u.setText(prefs.getString("url", ""))
            urlInput = u
            root.addView(u)

            root.addView(makeLabel("Secret word"))
            val s = EditText(this)
            s.inputType = InputType.TYPE_CLASS_TEXT
            s.setText(prefs.getString("secret", Config.SECRET))
            secretInput = s
            root.addView(s)
        }

        val start = Button(this)
        start.text = "Allow permissions and start"
        start.setOnClickListener { beginSetup() }
        root.addView(start)

        val syncBtn = Button(this)
        syncBtn.text = "Sync now"
        syncBtn.setOnClickListener { syncNow() }
        root.addView(syncBtn)

        statusView = TextView(this)
        statusView.setPadding(0, pad, 0, 0)
        root.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()

        // Second launch before setup finished: continue straight into the permission prompts.
        if (saved.isNotEmpty() && !prefs.getBoolean("setupDone", false)) {
            beginSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun makeLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        return t
    }

    private fun chosenName(): String {
        val g = teamGroup
        if (g != null) {
            val id = g.checkedRadioButtonId
            if (id == -1) return ""
            return g.findViewById<RadioButton>(id).text.toString()
        }
        return nameInput?.text?.toString()?.trim().orEmpty()
    }

    private fun refreshStatus() {
        val callOk = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusView.text = (if (callOk) "[OK] Call log allowed" else "[ ] Call log not allowed yet") + "\n" +
            (if (battOk) "[OK] Background running allowed" else "[ ] Background running not allowed yet") + "\n\n" +
            "Status: " + prefs.getString("status", "Not synced yet")
    }

    // Step 1: name and settings. Step 2: call log permission. Step 3: background permission. Then sync.
    private fun beginSetup() {
        val name = chosenName()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please choose or type your name first", Toast.LENGTH_LONG).show()
            return
        }
        val url = if (Config.URL.isNotBlank()) Config.URL else urlInput?.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "Enter the full https:// sheet link", Toast.LENGTH_LONG).show()
            return
        }
        val secret = secretInput?.text?.toString()?.trim().orEmpty()
        prefs.edit()
            .putString("employee", name)
            .putString("url", url)
            .putString("secret", if (secret.isNotEmpty()) secret else Config.SECRET)
            .apply()
        askCallLog()
    }

    private fun askCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_CALL_LOG)
        } else {
            askBattery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_LOG) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                askBattery()
            } else {
                Toast.makeText(this, "Call log permission is needed. Tap the button again and choose Allow.", Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun askBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivityForResult(i, REQ_BATTERY)
                return
            } catch (e: Exception) {
                // fall through and finish without it
            }
        }
        finishSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BATTERY) finishSetup()
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("setupDone", true).apply()
        scheduleSync()
        syncNow()
    }

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork("calltracker-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun syncNow() {
        statusView.text = "Status: syncing..."
        Thread {
            Sync.run(applicationContext)
            runOnUiThread { refreshStatus() }
        }.start()
    }
}
