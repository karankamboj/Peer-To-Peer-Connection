package com.example.project2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class NearbyLink(
    private val activity: Activity,
    private val serviceId: String = "com.example.project2.NEARBY",
    private val onStatus: (String) -> Unit = {}
) {
    private val client by lazy { Nearby.getConnectionsClient(activity) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "NearbyLink"

    private val connected = mutableSetOf<String>()
    val discovered: Channel<Pair<String, String>> = Channel(Channel.BUFFERED)
    val incoming: Channel<GameState> = Channel(Channel.BUFFERED)

    private fun has(p: String) =
        ActivityCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    private fun permsOk(): Boolean =
        has(Manifest.permission.BLUETOOTH_SCAN) &&
                has(Manifest.permission.BLUETOOTH_CONNECT) &&
                has(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun notify(msg: String) {
        Log.d(tag, msg)
        onStatus(msg)
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val txt = payload.asBytes()?.decodeToString() ?: return
                runCatching {
                    val msg = json.decodeFromString(GameState.serializer(), txt)
                    scope.launch { incoming.send(msg) }
                }.onFailure { e -> notify("JSON parse error: ${e.message}") }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycleCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            notify("Conn initiated with ${info.endpointName} — accepting")
            client.acceptConnection(endpointId, payloadCb)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connected += endpointId
                    notify("Connected to $endpointId")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    notify("Connection rejected")
                ConnectionsStatusCodes.STATUS_ERROR ->
                    notify("Connection error")
                else -> notify("Connection failed: ${result.status.statusCode}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            notify("Disconnected from $endpointId")
        }
    }

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            notify("Found: ${info.endpointName} ($endpointId)")
            scope.launch { discovered.send(endpointId to info.endpointName) }
        }
        override fun onEndpointLost(endpointId: String) {
            notify("Endpoint lost: $endpointId")
        }
    }

    fun startAdvertising(): Boolean {
        if (!permsOk()) { notify("Missing permissions to advertise"); return false }
        val opts = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startAdvertising(android.os.Build.MODEL ?: "Android", serviceId, lifecycleCb, opts)
            .addOnSuccessListener { notify("Advertising started") }
            .addOnFailureListener { e -> notify("Advertising failed: ${e.message}") }
        return true
    }

    fun startDiscovery(): Boolean {
        if (!permsOk()) { notify("Missing permissions to discover"); return false }
        val opts = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startDiscovery(serviceId, discoveryCb, opts)
            .addOnSuccessListener { notify("Discovery started") }
            .addOnFailureListener { e -> notify("Discovery failed: ${e.message}") }
        return true
    }

    fun requestConnection(endpointId: String) {
        client.requestConnection(android.os.Build.MODEL ?: "Android", endpointId, lifecycleCb)
            .addOnSuccessListener { notify("Requesting connection…") }
            .addOnFailureListener { e -> notify("requestConnection failed: ${e.message}") }
    }

    fun isConnected(): Boolean = connected.isNotEmpty()

    fun send(gs: GameState) {
        if (connected.isEmpty()) { notify("No connected peers"); return }
        val bytes = json.encodeToString(GameState.serializer(), gs).encodeToByteArray()
        val payload = Payload.fromBytes(bytes)
        connected.forEach { client.sendPayload(it, payload) }
        notify("Payload sent to ${connected.size} peer(s)")
    }

    fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        connected.toList().forEach { client.disconnectFromEndpoint(it) }
        connected.clear()
        notify("Stopped advertising/discovery; disconnected")
    }
}
