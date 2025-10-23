package com.example.project2

import android.os.Build
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button

    private lateinit var nearby: NearbyLink

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val needed = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val ok = needed.all { p ->
            grants[p] == true || ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }

        if (!ok) {
            Toast.makeText(this, "Permissions needed for Nearby", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensurePerms() {
        val missing = mutableListOf<String>()
        fun need(p: String) = ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED

        if (need(Manifest.permission.BLUETOOTH_SCAN)) missing += Manifest.permission.BLUETOOTH_SCAN
        if (need(Manifest.permission.BLUETOOTH_CONNECT)) missing += Manifest.permission.BLUETOOTH_CONNECT
        if (need(Manifest.permission.BLUETOOTH_ADVERTISE)) missing += Manifest.permission.BLUETOOTH_ADVERTISE
        if (need(Manifest.permission.ACCESS_FINE_LOCATION)) missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && need(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            missing += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        if (missing.isNotEmpty()) reqPerms.launch(missing.toTypedArray())
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend    = findViewById(R.id.btnSend)

        ensurePerms()

        nearby = NearbyLink(this) { msg ->
            runOnUiThread {
                status.text = "Status: $msg"
                // Quick surface: toast key transitions
                if (msg.contains("failed", ignoreCase = true) ||
                    msg.contains("Missing", ignoreCase = true) ||
                    msg.contains("No connected", ignoreCase = true)) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Show popup when messages arrive
        lifecycleScope.launch {
            for (msg in nearby.incoming) {
                runOnUiThread {
                    status.text = "Status: Message received"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Incoming message")
                        .setMessage(msg.payload["msg"] ?: "Hello, world!")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        val endpoints = linkedMapOf<String, String>() // id -> name
        lifecycleScope.launch {
            for ((id, name) in nearby.discovered) {
                endpoints[id] = name
                // Auto-show picker on first find
                if (endpoints.size == 1) showEndpointPicker(endpoints)
            }
        }

        // CONNECT: pick role first (more reliable)
        btnConnect.setOnClickListener {
            val roles = arrayOf("Host (Advertise)", "Join (Discover)")
            AlertDialog.Builder(this)
                .setTitle("Choose role")
                .setItems(roles) { _, which ->
                    endpoints.clear()
                    nearby.stopAll()
                    ensurePerms()
                    if (which == 0) {
                        // HOST
                        val advOk = nearby.startAdvertising()
                        status.text = "Status: ${if (advOk) "Advertising…" else "Missing permissions"}"
                    } else {
                        // JOIN
                        val disOk = nearby.startDiscovery()
                        status.text = "Status: ${if (disOk) "Discovering…" else "Missing permissions"}"
                        // Show picker immediately (and it will reopen when first result appears)
                        showEndpointPicker(endpoints)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // SEND
        btnSend.setOnClickListener {
            if (!nearby.isConnected()) {
                Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            nearby.send(GameState(payload = mapOf("msg" to "Hello, world!")))
            status.text = "Status: Sent"
        }
    }


    private fun showEndpointPicker(endpoints: Map<String, String>) {
        if (endpoints.isEmpty()) {
            Toast.makeText(this, "Searching for nearby devices…", Toast.LENGTH_SHORT).show()
            return
        }
        val items = endpoints.entries.map { (_, name) -> name }.toTypedArray()
        val ids = endpoints.keys.toList()

        AlertDialog.Builder(this)
            .setTitle("Select a device")
            .setItems(items) { _, which ->
                val endpointId = ids[which]
                status.text = "Status: Connecting to ${items[which]}…"
                nearby.requestConnection(endpointId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        nearby.stopAll()
    }
}
