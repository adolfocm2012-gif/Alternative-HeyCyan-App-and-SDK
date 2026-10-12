package com.fersaiyan.cyanbridge.devices.tunebuds

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

data class TuneBudsHotspotCredentials(
    val ssid: String,
    val password: String,
    val channel: Int = 0,
)

/**
 * Kept for compatibility with MainActivity and as a future fallback path.
 * Build #24 uses Wi-Fi Direct for the primary TuneBuds media transfer.
 */
class TuneBudsLocalHotspot(context: Context) {
    companion object {
        private const val START_TIMEOUT_MS = 30_000L
    }

    private val context = context.applicationContext

    internal val appContext: Context
        get() = context

    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var reservation:
        WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    suspend fun start(): TuneBudsHotspotCredentials {
        if (!hasWifiPermission()) {
            throw SecurityException(
                "Nearby Wi-Fi or location permission is required",
            )
        }

        stop()

        return withTimeout(START_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->

                continuation.invokeOnCancellation {
                    stop()
                }

                wifiManager.startLocalOnlyHotspot(
                    object :
                        WifiManager.LocalOnlyHotspotCallback() {

                        override fun onStarted(
                            startedReservation:
                                WifiManager.LocalOnlyHotspotReservation,
                        ) {
                            if (!continuation.isActive) {
                                startedReservation.close()
                                return
                            }

                            reservation =
                                startedReservation

                            val credentials =
                                readCredentials(
                                    startedReservation,
                                )

                            if (credentials == null) {
                                stop()

                                continuation.resumeWithException(
                                    IOException(
                                        "Android returned empty hotspot credentials",
                                    ),
                                )
                            } else {
                                continuation.resume(
                                    credentials,
                                )
                            }
                        }

                        override fun onStopped() {
                            reservation = null

                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException(
                                        "Local hotspot stopped before it was ready",
                                    ),
                                )
                            }
                        }

                        override fun onFailed(
                            reason: Int,
                        ) {
                            reservation = null

                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException(
                                        "Could not start local hotspot: $reason",
                                    ),
                                )
                            }
                        }
                    },
                    Handler(
                        Looper.getMainLooper(),
                    ),
                )
            }
        }
    }

    fun stop() {
        val current =
            reservation

        reservation =
            null

        runCatching {
            current?.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun readCredentials(
        current:
            WifiManager.LocalOnlyHotspotReservation,
    ): TuneBudsHotspotCredentials? {

        val ssid: String?
        val password: String?
        val channel: Int

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            val configuration:
                SoftApConfiguration =
                current.softApConfiguration

            ssid =
                configuration.ssid

            password =
                configuration.passphrase

            channel =
                0
        } else {
            val configuration =
                current.wifiConfiguration

            ssid =
                configuration?.SSID

            password =
                configuration?.preSharedKey

            channel =
                0
        }

        if (
            ssid.isNullOrBlank() ||
            password.isNullOrBlank()
        ) {
            return null
        }

        return TuneBudsHotspotCredentials(
            ssid = ssid,
            password = password,
            channel =
                channel.coerceIn(
                    0,
                    0xFF,
                ),
        )
    }

    private fun hasWifiPermission(): Boolean =
        hasWifiP2pPermission(
            context,
        )
}

enum class TuneBudsMediaType {
    PHOTO,
    VIDEO,
    AUDIO,
}

data class TuneBudsMediaItem(
    val remoteName: String,
    val fileName: String,
    val type: TuneBudsMediaType,
)

data class TuneBudsMediaSyncState(
    val detail: String = "Idle",
    val completed: Int = 0,
    val total: Int = 0,
    val lastError: String? = null,
)

private data class RawHttpDownloadResult(
    val copied: Long,
    val advertisedLength: Long,
    val readError: IOException?,
)

private class EmptyRemoteMediaException(
    val remoteName: String,
) : IOException(
    "TuneBuds returned an empty media file: $remoteName",
)

/**
 * TuneBuds / AB Mate media synchronization.
 *
 * E1749 build #41 skip zero-byte items even on transport errors:
 * - Restores the vendor-style Wi-Fi Direct path: E6 mode 2.
 * - Uses F2 as the authoritative P2P peer MAC.
 * - Uses the E7 endpoint as the authoritative media HTTP base URL.
 * - Android discovers and joins the glasses through Wi-Fi Direct.
 * - HTTP sockets are routed through the P2P Network/interface.
 * - Never sends EB, so device media is not deleted.
 * - Never sends an extra E2 around synchronization.
 */
class TuneBudsMediaSync(
    private val manager: TuneBudsManager,
    private val hotspot: TuneBudsLocalHotspot,
    private val temporaryDirectory: File,
) {
    companion object {
        private const val TAG =
            "TuneBudsMediaSync"

        private const val RAW_PREVIEW_UI_LIMIT =
            240

        private const val RAW_PREVIEW_LOG_LIMIT =
            4_000

        private const val MEDIA_DOWNLOAD_MAX_ATTEMPTS =
            12

        private const val MEDIA_DOWNLOAD_RETRY_MS =
            500L

        private const val RAW_HTTP_CONNECT_TIMEOUT_MS =
            20_000

        private const val RAW_HTTP_READ_TIMEOUT_MS =
            120_000

        private const val P2P_CONNECT_TIMEOUT_MS =
            45_000L

        private const val P2P_NETWORK_WAIT_MS =
            10_000L

        private const val P2P_CLEANUP_TIMEOUT_MS =
            4_000L
    }

    private val appContext:
        Context =
        hotspot.appContext

    private val connectivityManager:
        ConnectivityManager =
        appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as ConnectivityManager

    @Volatile
    private var p2pNetwork:
        Network? = null

    @Volatile
    private var p2pManager:
        WifiP2pManagerSingleton? = null

    @Volatile
    private var p2pCallback:
        WifiP2pManagerSingleton.WifiP2pCallback? = null


    suspend fun sync(
        onState:
            suspend (
                TuneBudsMediaSyncState,
            ) -> Unit = {},
        onProgress:
            suspend (
                TuneBudsMediaItem,
                Long,
                Long,
            ) -> Unit = { _, _, _ -> },
        onFile:
            suspend (
                TuneBudsMediaItem,
                File,
            ) -> Boolean,
    ): Result<Int> {

        var state =
            TuneBudsMediaSyncState(
                detail =
                    "D1/7: Preparando sincronización P2P",
            )

        Log.i(
            TAG,
            state.detail,
        )

        onState(
            state,
        )

        temporaryDirectory.mkdirs()

        try {
            state =
                state.copy(
                    detail =
                        "D1/7: Sin cierre extra de cámara",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            state =
                state.copy(
                    detail =
                        "D2/7: Creando hotspot local del teléfono",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            val credentials =
                hotspot.start()

            state =
                state.copy(
                    detail =
                        "D3/7: Hotspot listo; conectando las gafas",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            val endpoint =
                manager
                    .startFileManager(
                        hotspotSsid =
                            credentials.ssid,
                        hotspotPassword =
                            credentials.password,
                        channel =
                            credentials.channel,
                        timeoutMs =
                            45_000L,
                    )
                    ?: throw IOException(
                        "TuneBuds did not report its media server address",
                    )

            state =
                state.copy(
                    detail =
                        "D4/7: Servidor de las gafas recibido: $endpoint",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            val baseUrl =
                normalizeBaseUrl(
                    endpoint,
                )

            state =
                state.copy(
                    detail =
                        "D5/7: Leyendo ${baseUrl}media.config",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            val manifestBody =
                withContext(
                    Dispatchers.IO,
                ) {
                    rawHttpGetText(
                        normalizeBaseUrl(
                            baseUrl,
                        ) +
                            "media.config",
                    )
                }

            val rawPreviewForLog =
                manifestPreview(
                    manifestBody,
                    RAW_PREVIEW_LOG_LIMIT,
                )

            Log.i(
                TAG,
                "E1749 hotspot media.config RAW " +
                    "length=${manifestBody.length}: " +
                    rawPreviewForLog,
            )

            val items =
                parseManifest(
                    manifestBody,
                )

            state =
                state.copy(
                    total =
                        items.size,
                    detail =
                        "D6/7: Manifest recibido: ${items.size} archivo(s)",
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            if (items.isEmpty()) {
                val rawPreviewForLog =
                    manifestPreview(
                        manifestBody,
                        RAW_PREVIEW_LOG_LIMIT,
                    )

                Log.i(
                    TAG,
                    "E1749 media.config contains no compatible media: " +
                        rawPreviewForLog,
                )

                state =
                    state.copy(
                        total =
                            0,
                        completed =
                            0,
                        detail =
                            "Sincronización completada: no hay archivos pendientes",
                        lastError =
                            null,
                    )

                onState(
                    state,
                )

                return Result.success(
                    0,
                )
            }

            var completed =
                0

            var skippedEmpty =
                0

            for (item in items) {
                coroutineContext
                    .ensureActive()

                state =
                    state.copy(
                        detail =
                            "D7/7: Descargando ${completed + 1}/${items.size}: ${item.fileName}",
                        lastError =
                            null,
                    )

                Log.i(
                    TAG,
                    state.detail,
                )

                onState(
                    state,
                )

                val file =
                    try {
                        download(
                            baseUrl,
                            item,
                            onProgress,
                        ) { diagnostic ->
                            state =
                                state.copy(
                                    detail =
                                        diagnostic,
                                    lastError =
                                        null,
                                )

                            Log.i(
                                TAG,
                                diagnostic,
                            )

                            onState(
                                state,
                            )
                        }
                    } catch (
                        error:
                            EmptyRemoteMediaException,
                    ) {
                        skippedEmpty++

                        state =
                            state.copy(
                                detail =
                                    "D7/7: Archivo vacío saltado: ${item.fileName}",
                                lastError =
                                    "El servidor devolvió 0 bytes; se reintentará en otra sincronización",
                            )

                        Log.w(
                            TAG,
                            "Skipping empty TuneBuds media item: ${item.remoteName}",
                        )

                        onState(
                            state,
                        )

                        continue
                    }

                state =
                    state.copy(
                        detail =
                            "D7c/7: Archivo válido; importando ${item.fileName}",
                        lastError =
                            null,
                    )

                Log.i(
                    TAG,
                    state.detail,
                )

                onState(
                    state,
                )

                val imported =
                    try {
                        onFile(
                            item,
                            file,
                        )
                    } catch (
                        error:
                            Throwable,
                    ) {
                        state =
                            state.copy(
                                detail =
                                    "D7d/7: IMPORTACIÓN lanzó error",
                                lastError =
                                    error.message
                                        ?: error.javaClass.simpleName,
                            )

                        Log.e(
                            TAG,
                            state.detail,
                            error,
                        )

                        onState(
                            state,
                        )

                        throw error
                    } finally {
                        file.delete()
                    }

                if (!imported) {
                    state =
                        state.copy(
                            detail =
                                "D7d/7: IMPORTACIÓN FALLÓ",
                            lastError =
                                "onFile devolvió false para ${item.fileName}",
                        )

                    Log.e(
                        TAG,
                        state.detail,
                    )

                    onState(
                        state,
                    )

                    throw IOException(
                        "Could not import ${item.fileName}",
                    )
                }

                state =
                    state.copy(
                        detail =
                            "D7d/7: Importación OK: ${item.fileName}",
                        lastError =
                            null,
                    )

                Log.i(
                    TAG,
                    state.detail,
                )

                onState(
                    state,
                )

                completed++

                state =
                    state.copy(
                        completed =
                            completed,
                        detail =
                            "Importado $completed/${items.size}",
                        lastError =
                            null,
                    )

                Log.i(
                    TAG,
                    state.detail,
                )

                onState(
                    state,
                )
            }

            state =
                state.copy(
                    detail =
                        if (skippedEmpty > 0) {
                            "Sincronización completada: $completed importado(s), " +
                                "$skippedEmpty vacío(s) saltado(s)"
                        } else {
                            "Sincronización por hotspot completada"
                        },
                    lastError =
                        null,
                )

            Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            return Result.success(
                completed,
            )
        } catch (
            error:
                CancellationException,
        ) {
            throw error
        } catch (
            error:
                Throwable,
        ) {
            val failedAt =
                state.detail

            val message =
                error.message
                    ?: error.javaClass.simpleName

            Log.e(
                TAG,
                "E1749 hotspot sync failed at [$failedAt]: $message",
                error,
            )

            state =
                state.copy(
                    detail =
                        "FALLO en $failedAt",
                    lastError =
                        message,
                )

            onState(
                state,
            )

            return Result.failure(
                error,
            )
        } finally {
            withContext(
                NonCancellable,
            ) {
                /*
                 * E7 leaves the camera/file-transfer subsystem active on this
                 * firmware. Always send E2 before tearing down Wi-Fi so a sync
                 * (including an empty sync or a failed download) cannot leave
                 * Photo/Video controls stuck in a busy/transfer state.
                 */
                runCatching {
                    manager
                        .finishTransferBlocking()
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "E1749 camera/file-manager cleanup failed: " +
                            (error.message
                                ?: error.javaClass.simpleName),
                        error,
                    )
                }

                cleanupP2p()

                hotspot.stop()

                temporaryDirectory
                    .deleteRecursively()
            }
        }
    }

    /**
     * Starts E6 mode 2 / E7 over SPP and Android Wi-Fi Direct discovery in parallel.
     * F2 supplies the authoritative peer MAC; E7 supplies the authoritative HTTP endpoint.
     *
     * On Android 10+ the official TuneBuds flow asks the phone to become group owner
     * with intent 15. WifiP2pManagerSingleton exposes that intent in the companion
     * change that accompanies this file.
     */
    private suspend fun establishP2pFileManager(
        onDiagnostic:
            suspend (
                String,
            ) -> Unit,
    ): TuneBudsP2pFileManagerInfo =
        coroutineScope {

            fun report(
                message:
                    String,
            ) {
                launch {
                    onDiagnostic(
                        message,
                    )
                }
            }

            val wifi =
                WifiP2pManagerSingleton
                    .getInstance(
                        appContext,
                    )

            if (!wifi.canUseP2p()) {
                onDiagnostic(
                    "D2a/7: SIN permiso Nearby Wi-Fi / ubicación",
                )

                throw SecurityException(
                    "Nearby Wi-Fi permission is required for TuneBuds P2P sync",
                )
            }

            p2pManager =
                wifi

            val p2pSsid =
                "AiGlassDb_${
                    Random.nextInt(
                        0,
                        10_000,
                    )
                }"

            val connected =
                CompletableDeferred<
                    WifiP2pInfo
                >()

            var lastPeers:
                Collection<
                    WifiP2pDevice
                > =
                emptyList()

            fun choosePeer(
                peers:
                    Collection<
                        WifiP2pDevice
                    >,
            ): WifiP2pDevice? {

                val reportedMac =
                    normalizeMac(
                        manager
                            .state
                            .value
                            .p2pPeerAddress,
                    )

                val byMac =
                    if (
                        reportedMac.isNotBlank()
                    ) {
                        peers.firstOrNull {
                            normalizeMac(
                                it.deviceAddress,
                            ) ==
                                reportedMac
                        }
                    } else {
                        null
                    }

                if (byMac != null) {
                    return byMac
                }

                return peers.firstOrNull {
                    val name =
                        it.deviceName
                            ?.trim()
                            .orEmpty()

                    name.equals(
                        p2pSsid,
                        ignoreCase = true,
                    ) ||
                        name.contains(
                            p2pSsid,
                            ignoreCase = true,
                        )
                }
            }

            fun tryConnect(
                peers:
                    Collection<
                        WifiP2pDevice
                    >,
            ) {
                if (
                    connected.isCompleted ||
                    wifi.isConnecting() ||
                    wifi.isConnected()
                ) {
                    return
                }

                val target =
                    choosePeer(
                        peers,
                    )
                        ?: return

                Log.i(
                    TAG,
                    "E1749 P2P target: " +
                        "${target.deviceName}/${target.deviceAddress}, " +
                        "reported=${manager.state.value.p2pPeerAddress}",
                )

                report(
                    "D2c/7: Peer encontrado: " +
                        "${target.deviceAddress}",
                )

                wifi.connectToDevice(
                    target,
                    groupOwnerIntent = 15,
                )
            }

            val callback =
                object :
                    WifiP2pManagerSingleton
                    .WifiP2pCallback {

                    override fun onWifiP2pEnabled() {
                        Log.i(
                            TAG,
                            "Android Wi-Fi Direct enabled",
                        )
                    }

                    override fun onWifiP2pDisabled() {
                        report(
                            "D2a/7: Wi-Fi Direct desactivado en Android",
                        )

                        if (
                            !connected.isCompleted
                        ) {
                            connected.completeExceptionally(
                                IOException(
                                    "Android Wi-Fi Direct is disabled",
                                ),
                            )
                        }
                    }

                    override fun onPeersChanged(
                        peers:
                            Collection<
                                WifiP2pDevice
                            >,
                    ) {
                        lastPeers =
                            peers.toList()

                        Log.i(
                            TAG,
                            "E1749 P2P peers=" +
                                peers.joinToString {
                                    "${it.deviceName}/${it.deviceAddress}"
                                },
                        )

                        tryConnect(
                            peers,
                        )
                    }

                    override fun onThisDeviceChanged(
                        device:
                            WifiP2pDevice,
                    ) {
                        Log.d(
                            TAG,
                            "P2P local device: " +
                                "${device.deviceName}/${device.deviceAddress}",
                        )
                    }

                    override fun onConnected(
                        info:
                            WifiP2pInfo,
                    ) {
                        if (
                            info.groupFormed &&
                            !connected.isCompleted
                        ) {
                            Log.i(
                                TAG,
                                "E1749 P2P group formed; " +
                                    "phoneIsOwner=${info.isGroupOwner}, " +
                                    "owner=${info.groupOwnerAddress?.hostAddress}",
                            )

                            report(
                                "D2e/7: Grupo P2P formado; " +
                                    "móvilOwner=${info.isGroupOwner}",
                            )

                            connected.complete(
                                info,
                            )
                        }
                    }

                    override fun onDisconnected() {
                        Log.i(
                            TAG,
                            "E1749 P2P disconnected",
                        )
                    }

                    override fun onPeerDiscoveryStarted() {
                        Log.i(
                            TAG,
                            "E1749 P2P discovery started",
                        )
                    }

                    override fun onPeerDiscoveryFailed(
                        reason:
                            Int,
                    ) {
                        Log.w(
                            TAG,
                            "E1749 P2P discovery failed: $reason",
                        )

                        report(
                            "D2a/7: Descubrimiento P2P falló (código $reason)",
                        )
                    }

                    override fun onConnectRequestSent() {
                        Log.i(
                            TAG,
                            "E1749 P2P connect request accepted",
                        )

                        report(
                            "D2d/7: Solicitud P2P enviada",
                        )
                    }

                    override fun onConnectRequestFailed(
                        reason:
                            Int,
                    ) {
                        Log.w(
                            TAG,
                            "E1749 P2P connect request failed: $reason",
                        )

                        report(
                            "D2d/7: Solicitud P2P FALLÓ (código $reason)",
                        )
                    }

                    override fun connecting() {
                        Log.d(
                            TAG,
                            "E1749 P2P already connecting",
                        )
                    }

                    override fun cancelConnect() {
                        Log.d(
                            TAG,
                            "E1749 P2P connect cancelled",
                        )
                    }

                    override fun cancelConnectFail(
                        reason:
                            Int,
                    ) {
                        Log.w(
                            TAG,
                            "E1749 P2P cancel failed: $reason",
                        )
                    }

                    override fun retryAlsoFailed() {
                        if (
                            !connected.isCompleted
                        ) {
                            connected.completeExceptionally(
                                IOException(
                                    "Wi-Fi Direct could not connect to the TuneBuds peer",
                                ),
                            )
                        }
                    }
                }

            p2pCallback =
                callback

            wifi.resetFailCount()
            wifi.registerReceiver()
            wifi.addCallback(
                callback,
            )

            val handshake =
                async {
                    onDiagnostic(
                        "D2a/7: Solicitando E6 modo 2 + E7",
                    )

                    manager
                        .startFileManagerP2p(
                            p2pSsid =
                                p2pSsid,
                            timeoutMs =
                                P2P_CONNECT_TIMEOUT_MS,
                            onDiagnostic =
                                onDiagnostic,
                        )
                        ?: throw IOException(
                            "TuneBuds did not start its P2P file manager",
                        )
                }

            val peerWatcher =
                launch {
                    val peer =
                        manager
                            .state
                            .filter {
                                !it.p2pPeerAddress
                                    .isNullOrBlank()
                            }
                            .first()
                            .p2pPeerAddress

                    Log.i(
                        TAG,
                        "E1749 F2 peer available: $peer",
                    )

                    onDiagnostic(
                        "D2b/7: F2 recibido: $peer",
                    )

                    /*
                     * A peer list may have arrived before F2. Re-evaluate it
                     * immediately, then ask Android for a fresh list.
                     */
                    tryConnect(
                        lastPeers,
                    )

                    repeat(
                        4,
                    ) {
                        if (
                            connected.isCompleted
                        ) {
                            return@launch
                        }

                        wifi.requestPeers()

                        delay(
                            1_250L,
                        )
                    }
                }

            try {
                /*
                 * Start Android discovery after the E6/E7 coroutine is armed.
                 * Device resets on the shared wrapper's discovery timeout are
                 * disabled because TuneBuds uses its own SPP protocol.
                 */
                wifi.startPeerDiscovery(
                    allowDeviceResetOnTimeout =
                        false,
                )

                val info =
                    withTimeout(
                        P2P_CONNECT_TIMEOUT_MS,
                    ) {
                        connected.await()
                    }

                p2pNetwork =
                    waitForP2pNetwork(
                        P2P_NETWORK_WAIT_MS,
                    )

                val localAddress =
                    p2pLocalIpv4Address()

                Log.i(
                    TAG,
                    "E1749 P2P routing: " +
                        "network=${describeNetwork(p2pNetwork)}, " +
                        "local=${localAddress?.hostAddress}, " +
                        "groupOwner=${info.groupOwnerAddress?.hostAddress}",
                )

                if (
                    p2pNetwork == null &&
                    localAddress == null
                ) {
                    Log.w(
                        TAG,
                        "Android did not expose a dedicated P2P Network/interface; " +
                            "system routing will be used as final fallback",
                    )
                }

                val fileManagerInfo =
                    withTimeout(
                        P2P_CONNECT_TIMEOUT_MS,
                    ) {
                        handshake.await()
                    }

                Log.i(
                    TAG,
                    "E1749 P2P file manager ready: " +
                        "peer=${fileManagerInfo.peerAddress}, " +
                        "endpoint=${fileManagerInfo.endpoint}",
                )

                onDiagnostic(
                    "D2f/7: E7 recibido: ${fileManagerInfo.endpoint}",
                )

                fileManagerInfo
            } finally {
                peerWatcher.cancel()

                if (
                    !handshake.isCompleted
                ) {
                    handshake.cancel()
                }
            }
        }

    private suspend fun waitForP2pNetwork(
        timeoutMs:
            Long,
    ): Network? {

        val deadline =
            System.currentTimeMillis() +
                timeoutMs

        while (
            System.currentTimeMillis() <
            deadline
        ) {
            findP2pNetwork()
                ?.let {
                    return it
                }

            delay(
                250L,
            )
        }

        return findP2pNetwork()
    }

    private fun findP2pNetwork():
        Network? {

        return runCatching {
            connectivityManager
                .allNetworks
                .firstOrNull { network ->

                    val capabilities =
                        connectivityManager
                            .getNetworkCapabilities(
                                network,
                            )
                            ?: return@firstOrNull false

                    if (
                        !capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI,
                        ) ||
                        capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_VPN,
                        )
                    ) {
                        return@firstOrNull false
                    }

                    val properties =
                        connectivityManager
                            .getLinkProperties(
                                network,
                            )

                    val interfaceName =
                        properties
                            ?.interfaceName
                            .orEmpty()

                    val addresses =
                        properties
                            ?.linkAddresses
                            ?.mapNotNull {
                                it.address
                                    .hostAddress
                            }
                            .orEmpty()

                    interfaceName.contains(
                        "p2p",
                        ignoreCase = true,
                    ) ||
                        interfaceName.contains(
                            "wfd",
                            ignoreCase = true,
                        ) ||
                        addresses.any {
                            it.startsWith(
                                "192.168.49.",
                            )
                        }
                }
        }
            .getOrNull()
    }

    private fun p2pLocalIpv4Address():
        InetAddress? {

        return runCatching {
            Collections
                .list(
                    NetworkInterface
                        .getNetworkInterfaces(),
                )
                .asSequence()
                .filter { networkInterface ->
                    val name =
                        networkInterface
                            .name
                            .orEmpty()

                    name.contains(
                        "p2p",
                        ignoreCase = true,
                    ) ||
                        name.contains(
                            "wfd",
                            ignoreCase = true,
                        )
                }
                .flatMap { networkInterface ->
                    Collections
                        .list(
                            networkInterface
                                .inetAddresses,
                        )
                        .asSequence()
                }
                .firstOrNull { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress
                }
        }
            .getOrNull()
    }

    private fun describeNetwork(
        network:
            Network?,
    ): String {

        if (
            network == null
        ) {
            return "none"
        }

        return runCatching {
            val properties =
                connectivityManager
                    .getLinkProperties(
                        network,
                    )

            "if=${properties?.interfaceName}, " +
                "addresses=" +
                properties
                    ?.linkAddresses
                    ?.joinToString {
                        it.address.hostAddress
                            .orEmpty()
                    }
        }
            .getOrDefault(
                network.toString(),
            )
    }

    private fun normalizeMac(
        value:
            String?,
    ): String =
        value
            .orEmpty()
            .filter {
                it.isLetterOrDigit()
            }
            .uppercase()

    private suspend fun cleanupP2p() {
        val wifi =
            p2pManager

        val callback =
            p2pCallback

        p2pCallback =
            null

        p2pNetwork =
            null

        p2pManager =
            null

        if (
            wifi == null
        ) {
            return
        }

        if (
            callback != null
        ) {
            runCatching {
                wifi.removeCallback(
                    callback,
                )
            }
        }

        runCatching {
            wifi.stopP2pOperations()
        }

        runCatching {
            wifi.cancelP2pConnection()
        }

        withTimeoutOrNull(
            P2P_CLEANUP_TIMEOUT_MS,
        ) {
            suspendCancellableCoroutine<Unit> { continuation ->
                runCatching {
                    wifi.removeGroup { _ ->
                        if (
                            continuation.isActive
                        ) {
                            continuation.resume(
                                Unit,
                            )
                        }
                    }
                }.onFailure {
                    if (
                        continuation.isActive
                    ) {
                        continuation.resume(
                            Unit,
                        )
                    }
                }
            }
        }

        runCatching {
            wifi.unregisterReceiver()
        }
    }

    suspend fun fetchManifest(
        baseUrl:
            String,
    ): List<TuneBudsMediaItem> =
        withContext(
            Dispatchers.IO,
        ) {
            parseManifest(
                rawHttpGetText(
                    normalizeBaseUrl(
                        baseUrl,
                    ) +
                        "media.config",
                ),
            )
        }

    suspend fun downloadSingle(
        baseUrl:
            String,
        item:
            TuneBudsMediaItem,
    ): File =
        download(
            baseUrl =
                normalizeBaseUrl(
                    baseUrl,
                ),
            item =
                item,
            onProgress =
                { _, _, _ -> },
        )

    fun parseManifest(
        body:
            String,
    ): List<TuneBudsMediaItem> {

        val trimmed =
            body.trim()

        val names =
            runCatching {
                when {
                    trimmed.startsWith(
                        "[",
                    ) -> {
                        stringsFromArray(
                            JSONArray(
                                trimmed,
                            ),
                        )
                    }

                    trimmed.startsWith(
                        "{",
                    ) -> {
                        stringsFromArray(
                            JSONObject(
                                trimmed,
                            )
                                .optJSONArray(
                                    "files",
                                )
                                ?: JSONArray(),
                        )
                    }

                    else ->
                        emptyList()
                }
            }
                .getOrDefault(
                    emptyList(),
                )
                .ifEmpty {
                    trimmed
                        .lineSequence()
                        .map(
                            String::trim,
                        )
                        .filter {
                            it.isNotBlank() &&
                                !it.startsWith(
                                    "HTTP/",
                                ) &&
                                !it.contains(
                                    ": ",
                                )
                        }
                        .toList()
                }

        return names
            .mapNotNull(
                ::mediaItem,
            )
            .distinctBy {
                it.remoteName
            }
    }

    private suspend fun download(
        baseUrl:
            String,
        item:
            TuneBudsMediaItem,
        onProgress:
            suspend (
                TuneBudsMediaItem,
                Long,
                Long,
            ) -> Unit,
        onDiagnostic:
            suspend (
                String,
            ) -> Unit = {},
    ): File =
        withContext(
            Dispatchers.IO,
        ) {
            val output =
                File(
                    temporaryDirectory,
                    item.fileName,
                ).apply {
                    parentFile?.mkdirs()
                    delete()
                }

            val mediaUrl =
                baseUrl +
                    encodePath(
                        item.remoteName,
                    )

            var lastFailure:
                Throwable? = null

            var lastAdvertisedLength =
                -1L

            repeat(
                MEDIA_DOWNLOAD_MAX_ATTEMPTS,
            ) { index ->

                coroutineContext
                    .ensureActive()

                val attempt =
                    index + 1

                val resumeFrom =
                    output.length()

                Log.i(
                    TAG,
                    "HOTSPOT RESUME media download " +
                        "attempt $attempt/" +
                        "$MEDIA_DOWNLOAD_MAX_ATTEMPTS: " +
                        item.remoteName +
                        ", resumeFrom=$resumeFrom",
                )

                try {
                    val result =
                        rawHttpDownload(
                            mediaUrl,
                            output,
                            item,
                            onProgress,
                            resumeFrom,
                        )

                    lastAdvertisedLength =
                        result.advertisedLength

                    if (
                        result.readError != null
                    ) {
                        lastFailure =
                            result.readError

                        Log.w(
                            TAG,
                            "HOTSPOT RESUME stream incomplete; " +
                                "saved=${output.length()} bytes, " +
                                "expected=${result.advertisedLength}, " +
                                "error=${result.readError.message}",
                        )

                        onDiagnostic(
                            "D7a/7: Flujo incompleto: ${output.length()} bytes; reintentando",
                        )
                    }

                    onDiagnostic(
                        "D7a/7: Recibidos ${output.length()} bytes; validando ${item.fileName}",
                    )

                    val valid =
                        validateDownloadedMedia(
                            item,
                            output,
                        )

                    onDiagnostic(
                        if (valid) {
                            "D7b/7: Validación OK (${output.length()} bytes)"
                        } else {
                            "D7b/7: VALIDACIÓN FALLÓ (${output.length()} bytes)"
                        },
                    )

                    Log.i(
                        TAG,
                        "HOTSPOT RESUME saved=${output.length()}, " +
                            "advertised=${result.advertisedLength}, " +
                            "valid=$valid, " +
                            "file=${item.fileName}",
                    )

                    if (valid) {
                        Log.i(
                            TAG,
                            "Media accepted after resumable hotspot HTTP: " +
                                "${output.length()} bytes",
                        )

                        return@withContext output
                    }

                    if (
                        output.length() == 0L &&
                        attempt >= 3
                    ) {
                        onDiagnostic(
                            "D7b/7: 0 bytes tras $attempt intentos; saltando ${item.fileName}",
                        )

                        throw EmptyRemoteMediaException(
                            item.remoteName,
                        )
                    }

                    if (
                        result.readError == null
                    ) {
                        lastFailure =
                            IOException(
                                "Downloaded media is structurally incomplete",
                            )
                    }
                } catch (
                    error:
                        CancellationException,
                ) {
                    throw error
                } catch (
                    error:
                        EmptyRemoteMediaException,
                ) {
                    throw error
                } catch (
                    error:
                        Throwable,
                ) {
                    lastFailure =
                        error

                    Log.w(
                        TAG,
                        "HOTSPOT RESUME attempt $attempt failed; " +
                            "keeping ${output.length()} bytes: " +
                            (error.message
                                ?: error.javaClass.simpleName),
                        error,
                    )

                    /*
                     * Some stale/ghost media entries fail before an HTTP body is
                     * ever produced, so validation is never reached. If the same
                     * item remains at zero bytes for three attempts, treat it as
                     * unavailable for this sync and continue with the next item.
                     */
                    if (
                        output.length() == 0L &&
                        attempt >= 3
                    ) {
                        onDiagnostic(
                            "D7/7: 0 bytes tras $attempt intentos; " +
                                "saltando ${item.fileName}",
                        )

                        throw EmptyRemoteMediaException(
                            item.remoteName,
                        )
                    }
                }

                if (
                    attempt <
                    MEDIA_DOWNLOAD_MAX_ATTEMPTS
                ) {
                    delay(
                        MEDIA_DOWNLOAD_RETRY_MS,
                    )
                }
            }

            throw IOException(
                "TuneBuds resumable hotspot download failed " +
                    "after $MEDIA_DOWNLOAD_MAX_ATTEMPTS attempts; " +
                    "saved=${output.length()} bytes, " +
                    "advertised=" +
                    if (
                        lastAdvertisedLength > 0L
                    ) {
                        lastAdvertisedLength
                    } else {
                        "?"
                    },
                lastFailure,
            )
        }

    private suspend fun rawHttpDownload(
        urlString:
            String,
        output:
            File,
        item:
            TuneBudsMediaItem,
        onProgress:
            suspend (
                TuneBudsMediaItem,
                Long,
                Long,
            ) -> Unit,
        resumeFrom:
            Long = 0L,
    ): RawHttpDownloadResult {

        val url =
            URL(
                urlString,
            )

        validateHttpUrl(
            url,
        )

        val port =
            effectivePort(
                url,
            )

        val path =
            requestPath(
                url,
            )

        val requestedOffset =
            resumeFrom.coerceAtLeast(
                0L,
            )

        val socket =
            createRoutedSocket(
                url.host,
                port,
            )

        try {
            val networkOutput =
                BufferedOutputStream(
                    socket.getOutputStream(),
                )

            sendHttpGet(
                networkOutput,
                url,
                port,
                path,
                rangeStart =
                    requestedOffset
                        .takeIf {
                            it > 0L
                        },
            )

            val input =
                BufferedInputStream(
                    socket.getInputStream(),
                    128 * 1024,
                )

            val response =
                readHttpResponseHead(
                    input,
                    item.fileName,
                )

            val statusCode =
                response.first

            val responseLength =
                response.second[
                    "content-length"
                ]
                    ?.toLongOrNull()
                    ?: -1L

            val contentRange =
                response.second[
                    "content-range"
                ]

            val rangeTotal =
                contentRange
                    ?.substringAfterLast(
                        '/',
                        "",
                    )
                    ?.takeIf {
                        it.isNotBlank() &&
                            it != "*"
                    }
                    ?.toLongOrNull()

            val rangeStart =
                contentRange
                    ?.substringAfter(
                        "bytes ",
                        "",
                    )
                    ?.substringBefore(
                        '-',
                        "",
                    )
                    ?.trim()
                    ?.toLongOrNull()

            val advertisedLength =
                rangeTotal
                    ?: if (
                        statusCode == 206 &&
                        requestedOffset > 0L &&
                        responseLength >= 0L
                    ) {
                        requestedOffset +
                            responseLength
                    } else {
                        responseLength
                    }

            val transferEncoding =
                response.second[
                    "transfer-encoding"
                ]

            if (
                transferEncoding
                    ?.contains(
                        "chunked",
                        ignoreCase = true,
                    ) == true
            ) {
                throw IOException(
                    "TuneBuds returned chunked HTTP, unsupported for media transfer",
                )
            }

            var appendOffset =
                0L

            var bytesToDiscard =
                0L

            when {
                requestedOffset <= 0L -> {
                    appendOffset =
                        0L
                }

                statusCode == 206 -> {
                    if (
                        rangeStart != null &&
                        rangeStart !=
                        requestedOffset
                    ) {
                        throw IOException(
                            "TuneBuds resumed at byte $rangeStart " +
                                "instead of requested byte $requestedOffset",
                        )
                    }

                    appendOffset =
                        requestedOffset
                }

                statusCode == 200 -> {
                    /*
                     * Some tiny embedded HTTP servers ignore Range. Preserve the
                     * partial file and consume the already-saved prefix from the
                     * new full response before appending new bytes.
                     */
                    appendOffset =
                        requestedOffset

                    bytesToDiscard =
                        requestedOffset

                    Log.w(
                        TAG,
                        "TuneBuds ignored Range; using manual prefix skip " +
                            "of $bytesToDiscard bytes",
                    )
                }

                else -> {
                    throw IOException(
                        "Unexpected HTTP $statusCode while resuming " +
                            "at byte $requestedOffset",
                    )
                }
            }

            Log.i(
                TAG,
                "HOTSPOT RESUME HTTP response=$statusCode, " +
                    "contentLength=$responseLength, " +
                    "contentRange=$contentRange, " +
                    "resume=$requestedOffset, " +
                    "advertised=$advertisedLength, " +
                    "file=${item.fileName}",
            )

            var readError:
                IOException? = null

            if (
                bytesToDiscard >
                0L
            ) {
                var remainingSkip =
                    bytesToDiscard

                val skipBuffer =
                    ByteArray(
                        64 * 1024,
                    )

                while (
                    remainingSkip >
                    0L
                ) {
                    coroutineContext
                        .ensureActive()

                    val count =
                        try {
                            input.read(
                                skipBuffer,
                                0,
                                minOf(
                                    skipBuffer.size.toLong(),
                                    remainingSkip,
                                ).toInt(),
                            )
                        } catch (
                            error:
                                IOException,
                        ) {
                            readError =
                                error
                            break
                        }

                    if (
                        count <
                        0
                    ) {
                        readError =
                            IOException(
                                "Connection ended while skipping the " +
                                    "already-downloaded prefix at " +
                                    "${bytesToDiscard - remainingSkip}/$bytesToDiscard bytes",
                            )
                        break
                    }

                    if (
                        count >
                        0
                    ) {
                        remainingSkip -=
                            count
                    }
                }

                if (
                    readError != null
                ) {
                    return RawHttpDownloadResult(
                        copied =
                            output.length(),
                        advertisedLength =
                            advertisedLength,
                        readError =
                            readError,
                    )
                }
            }

            val expectedBodyBytes =
                when {
                    responseLength < 0L ->
                        -1L

                    statusCode == 200 &&
                        bytesToDiscard > 0L ->
                        (
                            responseLength -
                                bytesToDiscard
                            )
                            .coerceAtLeast(
                                0L,
                            )

                    else ->
                        responseLength
                }

            var copiedThisAttempt =
                0L

            RandomAccessFile(
                output,
                "rw",
            ).use { target ->

                if (
                    appendOffset <=
                    0L
                ) {
                    target.setLength(
                        0L,
                    )

                    target.seek(
                        0L,
                    )
                } else {
                    if (
                        target.length() <
                        appendOffset
                    ) {
                        throw IOException(
                            "Partial file is shorter than resume offset",
                        )
                    }

                    target.setLength(
                        appendOffset,
                    )

                    target.seek(
                        appendOffset,
                    )
                }

                val buffer =
                    ByteArray(
                        128 * 1024,
                    )

                while (true) {
                    coroutineContext
                        .ensureActive()

                    if (
                        expectedBodyBytes >=
                        0L &&
                        copiedThisAttempt >=
                        expectedBodyBytes
                    ) {
                        break
                    }

                    val maxRead =
                        if (
                            expectedBodyBytes >=
                            0L
                        ) {
                            minOf(
                                buffer.size.toLong(),
                                expectedBodyBytes -
                                    copiedThisAttempt,
                            ).toInt()
                        } else {
                            buffer.size
                        }

                    if (
                        maxRead <=
                        0
                    ) {
                        break
                    }

                    val count =
                        try {
                            input.read(
                                buffer,
                                0,
                                maxRead,
                            )
                        } catch (
                            error:
                                IOException,
                        ) {
                            readError =
                                error
                            break
                        }

                    if (
                        count <
                        0
                    ) {
                        break
                    }

                    if (
                        count ==
                        0
                    ) {
                        continue
                    }

                    target.write(
                        buffer,
                        0,
                        count,
                    )

                    copiedThisAttempt +=
                        count

                    val totalSaved =
                        appendOffset +
                            copiedThisAttempt

                    onProgress(
                        item,
                        totalSaved,
                        advertisedLength,
                    )
                }
            }

            if (
                readError == null &&
                expectedBodyBytes >=
                0L &&
                copiedThisAttempt <
                expectedBodyBytes
            ) {
                readError =
                    IOException(
                        "Premature EOF after $copiedThisAttempt/" +
                            "$expectedBodyBytes bytes in this attempt",
                    )
            }

            return RawHttpDownloadResult(
                copied =
                    output.length(),
                advertisedLength =
                    advertisedLength,
                readError =
                    readError,
            )
        } finally {
            runCatching {
                socket.close()
            }
        }
    }

    private fun rawHttpGetText(
        urlString:
            String,
    ): String {

        val url =
            URL(
                urlString,
            )

        validateHttpUrl(
            url,
        )

        val port =
            effectivePort(
                url,
            )

        val socket =
            createRoutedSocket(
                url.host,
                port,
            )

        try {
            val output =
                BufferedOutputStream(
                    socket.getOutputStream(),
                )

            sendHttpGet(
                output,
                url,
                port,
                requestPath(
                    url,
                ),
            )

            val input =
                BufferedInputStream(
                    socket.getInputStream(),
                    32 * 1024,
                )

            val response =
                readHttpResponseHead(
                    input,
                    "media.config",
                )

            val transferEncoding =
                response.second[
                    "transfer-encoding"
                ]

            if (
                transferEncoding
                    ?.contains(
                        "chunked",
                        ignoreCase = true,
                    ) == true
            ) {
                return readChunkedText(
                    input,
                )
            }

            val expected =
                response.second[
                    "content-length"
                ]
                    ?.toIntOrNull()

            val bytes =
                ByteArrayOutputStream()

            val buffer =
                ByteArray(
                    16 * 1024,
                )

            while (true) {
                val count =
                    input.read(
                        buffer,
                    )

                if (
                    count < 0
                ) {
                    break
                }

                if (
                    count > 0
                ) {
                    bytes.write(
                        buffer,
                        0,
                        count,
                    )

                    if (
                        expected != null &&
                        bytes.size() >=
                        expected
                    ) {
                        break
                    }
                }
            }

            val result =
                bytes
                    .toByteArray()

            val finalBytes =
                if (
                    expected != null &&
                    expected >= 0 &&
                    result.size >
                    expected
                ) {
                    result.copyOf(
                        expected,
                    )
                } else {
                    result
                }

            return String(
                finalBytes,
                StandardCharsets.UTF_8,
            )
        } finally {
            runCatching {
                socket.close()
            }
        }
    }

    private fun createRoutedSocket(
        host:
            String,
        port:
            Int,
    ): Socket {

        val socket =
            try {
                p2pNetwork
                    ?.socketFactory
                    ?.createSocket()
            } catch (
                error:
                    Throwable,
            ) {
                Log.w(
                    TAG,
                    "Hotspot routed socketFactory failed: ${error.message}",
                )
                null
            }
                ?: Socket()

        socket.soTimeout =
            RAW_HTTP_READ_TIMEOUT_MS

        if (
            p2pNetwork == null
        ) {
            p2pLocalIpv4Address()
                ?.let { local ->
                    runCatching {
                        socket.bind(
                            InetSocketAddress(
                                local,
                                0,
                            ),
                        )
                    }.onFailure {
                        Log.w(
                            TAG,
                            "Could not bind HTTP socket to P2P local address " +
                                "${local.hostAddress}: ${it.message}",
                        )
                    }
                }
        }

        socket.connect(
            InetSocketAddress(
                host,
                port,
            ),
            RAW_HTTP_CONNECT_TIMEOUT_MS,
        )

        return socket
    }

    private fun validateHttpUrl(
        url:
            URL,
    ) {
        if (
            !url.protocol.equals(
                "http",
                ignoreCase = true,
            )
        ) {
            throw IOException(
                "TuneBuds transfer only supports HTTP",
            )
        }

        if (
            url.host.isBlank()
        ) {
            throw IOException(
                "TuneBuds returned an invalid HTTP endpoint",
            )
        }
    }

    private fun effectivePort(
        url:
            URL,
    ): Int =
        if (
            url.port >
            0
        ) {
            url.port
        } else {
            /*
             * The legacy TuneBuds transfer client defaults to 8080
             * when E7 omits an explicit port.
             */
            8080
        }

    private fun requestPath(
        url:
            URL,
    ): String =
        if (
            url.file.isNullOrBlank()
        ) {
            "/"
        } else {
            url.file
        }

    private fun sendHttpGet(
        output:
            BufferedOutputStream,
        url:
            URL,
        port:
            Int,
        path:
            String,
        rangeStart:
            Long? = null,
    ) {
        val hostHeader =
            if (
                port ==
                80
            ) {
                url.host
            } else {
                "${url.host}:$port"
            }

        val request =
            buildString {
                append(
                    "GET $path HTTP/1.1\r\n",
                )

                append(
                    "Host: $hostHeader\r\n",
                )

                append(
                    "User-Agent: TSClient/1.0\r\n",
                )

                append(
                    "Connection: close\r\n",
                )

                append(
                    "Accept: */*\r\n",
                )

                append(
                    "Accept-Encoding: identity\r\n",
                )

                rangeStart
                    ?.takeIf {
                        it > 0L
                    }
                    ?.let { start ->
                        append(
                            "Range: bytes=$start-\r\n",
                        )
                    }

                append(
                    "\r\n",
                )
            }

        output.write(
            request.toByteArray(
                StandardCharsets.US_ASCII,
            ),
        )

        output.flush()
    }

    private fun readHttpResponseHead(
        input:
            BufferedInputStream,
        resource:
            String,
    ): Pair<
        Int,
        Map<
            String,
            String
        >
    > {
        val statusLine =
            readHttpLine(
                input,
            )
                ?: throw IOException(
                    "TuneBuds returned no HTTP status for $resource",
                )

        val statusCode =
            statusLine
                .split(
                    ' ',
                )
                .getOrNull(
                    1,
                )
                ?.toIntOrNull()
                ?: throw IOException(
                    "Invalid TuneBuds HTTP status: $statusLine",
                )

        if (
            statusCode !in
            200..299
        ) {
            throw IOException(
                "TuneBuds HTTP $statusCode for $resource",
            )
        }

        val headers =
            mutableMapOf<
                String,
                String
            >()

        while (true) {
            val line =
                readHttpLine(
                    input,
                )
                    ?: break

            if (
                line.isEmpty()
            ) {
                break
            }

            val separator =
                line.indexOf(
                    ':',
                )

            if (
                separator >
                0
            ) {
                val name =
                    line
                        .substring(
                            0,
                            separator,
                        )
                        .trim()
                        .lowercase()

                val value =
                    line
                        .substring(
                            separator + 1,
                        )
                        .trim()

                headers[
                    name
                ] =
                    value
            }
        }

        return statusCode to
            headers
    }

    private fun readChunkedText(
        input:
            BufferedInputStream,
    ): String {

        val bytes =
            ByteArrayOutputStream()

        while (true) {
            val sizeLine =
                readHttpLine(
                    input,
                )
                    ?: throw IOException(
                        "Unexpected EOF in chunked media.config",
                    )

            val size =
                sizeLine
                    .substringBefore(
                        ';',
                    )
                    .trim()
                    .toIntOrNull(
                        16,
                    )
                    ?: throw IOException(
                        "Invalid HTTP chunk size: $sizeLine",
                    )

            if (
                size ==
                0
            ) {
                while (true) {
                    val trailer =
                        readHttpLine(
                            input,
                        )
                            ?: break

                    if (
                        trailer.isEmpty()
                    ) {
                        break
                    }
                }

                break
            }

            var remaining =
                size

            val buffer =
                ByteArray(
                    minOf(
                        16 * 1024,
                        size,
                    ),
                )

            while (
                remaining >
                0
            ) {
                val count =
                    input.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            remaining,
                        ),
                    )

                if (
                    count <
                    0
                ) {
                    throw IOException(
                        "Unexpected EOF inside HTTP chunk",
                    )
                }

                bytes.write(
                    buffer,
                    0,
                    count,
                )

                remaining -=
                    count
            }

            readHttpLine(
                input,
            )
        }

        return String(
            bytes.toByteArray(),
            StandardCharsets.UTF_8,
        )
    }

    private fun readHttpLine(
        input:
            BufferedInputStream,
    ): String? {

        val bytes =
            ByteArrayOutputStream()

        var sawAnyByte =
            false

        while (true) {
            val value =
                input.read()

            if (
                value <
                0
            ) {
                if (
                    !sawAnyByte
                ) {
                    return null
                }

                break
            }

            sawAnyByte =
                true

            if (
                value ==
                '\n'.code
            ) {
                break
            }

            if (
                value !=
                '\r'.code
            ) {
                bytes.write(
                    value,
                )
            }

            if (
                bytes.size() >
                16 * 1024
            ) {
                throw IOException(
                    "TuneBuds returned an oversized HTTP header line",
                )
            }
        }

        return String(
            bytes.toByteArray(),
            StandardCharsets.ISO_8859_1,
        )
    }

    private fun validateDownloadedMedia(
        item:
            TuneBudsMediaItem,
        file:
            File,
    ): Boolean {

        if (
            !file.exists() ||
            file.length() <=
            0L
        ) {
            return false
        }

        return when (
            item.type
        ) {
            TuneBudsMediaType.VIDEO ->
                isCompleteMp4(
                    file,
                )

            TuneBudsMediaType.PHOTO ->
                isCompleteJpeg(
                    file,
                )

            TuneBudsMediaType.AUDIO ->
                isPlausibleAudio(
                    file,
                )
        }
    }

    private fun isCompleteMp4(
        file:
            File,
    ): Boolean {

        if (
            file.length() <
            16L
        ) {
            return false
        }

        val strictValid =
            runCatching {
                RandomAccessFile(
                    file,
                    "r",
                ).use { raf ->

                    val fileLength =
                        raf.length()

                    var position =
                        0L

                    var hasFtyp =
                        false

                    var hasMoov =
                        false

                    var hasMdat =
                        false

                    while (
                        position <
                        fileLength
                    ) {
                        if (
                            fileLength -
                            position <
                            8L
                        ) {
                            return@use false
                        }

                        raf.seek(
                            position,
                        )

                        val size32 =
                            raf
                                .readInt()
                                .toLong() and
                                0xffffffffL

                        val typeBytes =
                            ByteArray(
                                4,
                            )

                        raf.readFully(
                            typeBytes,
                        )

                        val type =
                            String(
                                typeBytes,
                                StandardCharsets.ISO_8859_1,
                            )

                        var headerSize =
                            8L

                        val boxSize =
                            when (
                                size32
                            ) {
                                0L -> {
                                    fileLength -
                                        position
                                }

                                1L -> {
                                    if (
                                        fileLength -
                                        position <
                                        16L
                                    ) {
                                        return@use false
                                    }

                                    headerSize =
                                        16L

                                    val extended =
                                        raf.readLong()

                                    if (
                                        extended <=
                                        0L
                                    ) {
                                        return@use false
                                    }

                                    extended
                                }

                                else ->
                                    size32
                            }

                        if (
                            boxSize <
                            headerSize
                        ) {
                            return@use false
                        }

                        val boxEnd =
                            position +
                                boxSize

                        if (
                            boxEnd <
                            position ||
                            boxEnd >
                            fileLength
                        ) {
                            return@use false
                        }

                        when (
                            type
                        ) {
                            "ftyp" ->
                                hasFtyp =
                                    true

                            "moov" ->
                                hasMoov =
                                    true

                            "mdat" ->
                                hasMdat =
                                    true
                        }

                        position =
                            boxEnd
                    }

                    position ==
                        fileLength &&
                        hasFtyp &&
                        hasMoov &&
                        hasMdat
                }
            }
                .getOrDefault(
                    false,
                )

        if (
            strictValid
        ) {
            Log.i(
                TAG,
                "MP4 strict validation OK; bytes=${file.length()}",
            )

            return true
        }

        Log.w(
            TAG,
            "MP4 strict box validation failed; " +
                "trying Android MediaExtractor; bytes=${file.length()}",
        )

        /*
         * Camera MP4 files do not always match the simplistic top-level box
         * assumptions above. Android's own media parser is a better final
         * authority for whether the downloaded file exposes a real video track.
         */
        return runCatching {
            val extractor =
                MediaExtractor()

            try {
                extractor.setDataSource(
                    file.absolutePath,
                )

                var hasVideoTrack =
                    false

                var videoMime:
                    String? = null

                for (
                    index in
                    0 until
                    extractor.trackCount
                ) {
                    val format =
                        extractor.getTrackFormat(
                            index,
                        )

                    val mime =
                        format.getString(
                            android.media.MediaFormat.KEY_MIME,
                        )

                    if (
                        mime
                            ?.startsWith(
                                "video/",
                                ignoreCase = true,
                            ) == true
                    ) {
                        hasVideoTrack =
                            true

                        videoMime =
                            mime

                        break
                    }
                }

                Log.i(
                    TAG,
                    "MP4 Android parser fallback: " +
                        "accepted=$hasVideoTrack, " +
                        "tracks=${extractor.trackCount}, " +
                        "videoMime=$videoMime, " +
                        "bytes=${file.length()}",
                )

                hasVideoTrack
            } finally {
                extractor.release()
            }
        }
            .getOrElse { error ->
                Log.w(
                    TAG,
                    "MP4 Android parser fallback failed: " +
                        (error.message
                            ?: error.javaClass.simpleName),
                    error,
                )

                false
            }
    }

    private fun isCompleteJpeg(
        file:
            File,
    ): Boolean {

        if (
            file.length() <
            4L
        ) {
            return false
        }

        return runCatching {
            RandomAccessFile(
                file,
                "r",
            ).use { raf ->

                val length =
                    raf.length()

                val firstCount =
                    minOf(
                        16,
                        length.toInt(),
                    )

                val firstBytes =
                    ByteArray(
                        firstCount,
                    )

                raf.seek(
                    0L,
                )

                raf.readFully(
                    firstBytes,
                )

                val tailCount =
                    minOf(
                        32,
                        length.toInt(),
                    )

                val tailBytes =
                    ByteArray(
                        tailCount,
                    )

                raf.seek(
                    length -
                        tailCount,
                )

                raf.readFully(
                    tailBytes,
                )

                fun hex(
                    bytes:
                        ByteArray,
                ): String =
                    bytes.joinToString(
                        separator = " ",
                    ) { value ->
                        "%02X".format(
                            value.toInt() and 0xFF,
                        )
                    }

                val hasSoi =
                    firstBytes.size >=
                        2 &&
                        (firstBytes[0].toInt() and 0xFF) ==
                        0xFF &&
                        (firstBytes[1].toInt() and 0xFF) ==
                        0xD8

                var position =
                    length -
                        2L

                var hasEoi =
                    false

                while (
                    position >=
                    2L
                ) {
                    raf.seek(
                        position,
                    )

                    val a =
                        raf.readUnsignedByte()

                    val b =
                        raf.readUnsignedByte()

                    if (
                        a ==
                        0xFF &&
                        b ==
                        0xD9
                    ) {
                        hasEoi =
                            true

                        break
                    }

                    position--
                }

                if (
                    hasSoi &&
                    hasEoi
                ) {
                    Log.i(
                        TAG,
                        "JPEG markers OK; bytes=$length",
                    )

                    return@use true
                }

                Log.w(
                    TAG,
                    "JPEG marker check failed; " +
                        "bytes=$length, " +
                        "SOI=$hasSoi, EOI=$hasEoi, " +
                        "first=${hex(firstBytes)}, " +
                        "tail=${hex(tailBytes)}",
                )

                /*
                 * Some camera/embedded HTTP combinations produce JPEG files that
                 * Android can decode even when the physical stream does not end
                 * with a textbook EOI marker. Ask Android's real JPEG decoder
                 * before rejecting the media.
                 */
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds =
                            true
                    }

                BitmapFactory.decodeFile(
                    file.absolutePath,
                    options,
                )

                val decoderAccepted =
                    options.outWidth >
                        0 &&
                        options.outHeight >
                        0

                Log.i(
                    TAG,
                    "JPEG Android decoder fallback: " +
                        "accepted=$decoderAccepted, " +
                        "width=${options.outWidth}, " +
                        "height=${options.outHeight}, " +
                        "mime=${options.outMimeType}",
                )

                decoderAccepted
            }
        }
            .getOrDefault(
                false,
            )
    }

    private fun isPlausibleAudio(
        file:
            File,
    ): Boolean {

        if (
            file.length() <
            12L
        ) {
            return false
        }

        return runCatching {
            RandomAccessFile(
                file,
                "r",
            ).use { raf ->

                val header =
                    ByteArray(
                        12,
                    )

                raf.readFully(
                    header,
                )

                val prefix4 =
                    String(
                        header,
                        0,
                        4,
                        StandardCharsets.ISO_8859_1,
                    )

                prefix4 ==
                    "OggS" ||
                    prefix4 ==
                    "RIFF"
            }
        }
            .getOrDefault(
                false,
            )
    }

    private fun manifestPreview(
        body:
            String,
        limit:
            Int,
    ): String {

        if (
            body.isEmpty()
        ) {
            return "<VACÍO>"
        }

        val escaped =
            buildString {
                body.forEach { char ->
                    when (
                        char
                    ) {
                        '\r' ->
                            append(
                                "\\r",
                            )

                        '\n' ->
                            append(
                                "\\n",
                            )

                        '\t' ->
                            append(
                                "\\t",
                            )

                        else -> {
                            if (
                                char.code in
                                0x20..0x7E ||
                                char.code >=
                                0xA0
                            ) {
                                append(
                                    char,
                                )
                            } else {
                                append(
                                    "\\u%04x"
                                        .format(
                                            char.code,
                                        ),
                                )
                            }
                        }
                    }
                }
            }

        return if (
            escaped.length <=
            limit
        ) {
            escaped
        } else {
            escaped.take(
                limit,
            ) +
                "…"
        }
    }

    private fun stringsFromArray(
        array:
            JSONArray,
    ): List<String> =
        buildList {
            for (
                index in
                0 until
                array.length()
            ) {
                when (
                    val value =
                        array.opt(
                            index,
                        )
                ) {
                    is String ->
                        add(
                            value,
                        )

                    is JSONObject -> {
                        value
                            .optString(
                                "name",
                            )
                            .takeIf(
                                String::isNotBlank,
                            )
                            ?.let(
                                ::add,
                            )
                    }
                }
            }
        }

    private fun mediaItem(
        value:
            String,
    ): TuneBudsMediaItem? {

        val remote =
            value
                .trim()
                .replace(
                    '\\',
                    '/',
                )
                .trimStart(
                    '/',
                )

        if (
            remote.isBlank() ||
            remote.contains(
                "../",
            )
        ) {
            return null
        }

        var fileName =
            File(
                remote,
            ).name

        val lower =
            fileName.lowercase()

        val type =
            when {
                lower.endsWith(
                    ".jpg",
                ) ||
                    lower.endsWith(
                        ".jpeg",
                    ) -> {
                    TuneBudsMediaType.PHOTO
                }

                lower.endsWith(
                    ".mp4",
                ) ||
                    lower.startsWith(
                        "video-",
                    ) -> {
                    TuneBudsMediaType.VIDEO
                }

                lower.endsWith(
                    ".opus",
                ) ||
                    lower.endsWith(
                        ".ogg",
                    ) ||
                    lower.endsWith(
                        ".wav",
                    ) -> {
                    TuneBudsMediaType.AUDIO
                }

                else ->
                    return null
            }

        if (
            type ==
            TuneBudsMediaType.VIDEO &&
            '.' !in
            fileName
        ) {
            fileName +=
                ".mp4"
        }

        return TuneBudsMediaItem(
            remoteName =
                remote,
            fileName =
                fileName,
            type =
                type,
        )
    }

    private fun normalizeBaseUrl(
        value:
            String,
    ): String {

        val trimmed =
            value.trim()

        require(
            trimmed.startsWith(
                "http://",
            )
        ) {
            "TuneBuds returned an invalid media server address"
        }

        return if (
            trimmed.endsWith(
                '/',
            )
        ) {
            trimmed
        } else {
            "$trimmed/"
        }
    }

    private fun encodePath(
        path:
            String,
    ): String =
        path
            .split(
                '/',
            )
            .filter(
                String::isNotBlank,
            )
            .joinToString(
                "/",
            ) { segment ->
                URLEncoder
                    .encode(
                        segment,
                        StandardCharsets.UTF_8
                            .name(),
                    )
                    .replace(
                        "+",
                        "%20",
                    )
            }
}
