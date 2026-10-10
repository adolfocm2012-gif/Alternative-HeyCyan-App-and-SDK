package com.fersaiyan.cyanbridge.devices.tunebuds

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TuneBudsHotspotCredentials(
    val ssid: String,
    val password: String,
    val channel: Int = 0,
)

class TuneBudsLocalHotspot(context: Context) {
    companion object {
        private const val START_TIMEOUT_MS = 30_000L
    }

    private val context = context.applicationContext

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

                                continuation
                                    .resumeWithException(
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
                                continuation
                                    .resumeWithException(
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
                                continuation
                                    .resumeWithException(
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
            5

        private const val MEDIA_DOWNLOAD_RETRY_MS =
            750L

        private const val RAW_HTTP_CONNECT_TIMEOUT_MS =
            20_000

        private const val RAW_HTTP_READ_TIMEOUT_MS =
            120_000

        private val CLIENT =
            OkHttpClient
                .Builder()
                .connectTimeout(
                    20,
                    TimeUnit.SECONDS,
                )
                .readTimeout(
                    120,
                    TimeUnit.SECONDS,
                )
                .writeTimeout(
                    30,
                    TimeUnit.SECONDS,
                )
                .build()
    }

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
                    "D1/7: Preparando sincronización",
            )

        android.util.Log.i(
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

            android.util.Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            state =
                state.copy(
                    detail =
                        "D2/7: Creando hotspot del teléfono",
                    lastError =
                        null,
                )

            android.util.Log.i(
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
                        "D3/7: Hotspot creado (${credentials.ssid})",
                    lastError =
                        null,
                )

            android.util.Log.i(
                TAG,
                "D3 hotspot created: " +
                    "SSID=${credentials.ssid}, " +
                    "channel=${credentials.channel}",
            )

            onState(
                state,
            )

            state =
                state.copy(
                    detail =
                        "D4/7: Conectando gafas al hotspot",
                    lastError =
                        null,
                )

            android.util.Log.i(
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
                    )
                    ?: throw IOException(
                        "TuneBuds did not report its media server address",
                    )

            state =
                state.copy(
                    detail =
                        "D5/7: Servidor recibido: $endpoint",
                    lastError =
                        null,
                )

            android.util.Log.i(
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
                        "D6/7: Leyendo ${baseUrl}media.config",
                    lastError =
                        null,
                )

            android.util.Log.i(
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
                    getText(
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

            android.util.Log.i(
                TAG,
                "E1749 media.config RAW " +
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
                        "D7/7: Manifest recibido: ${items.size} archivo(s)",
                    lastError =
                        null,
                )

            android.util.Log.i(
                TAG,
                state.detail,
            )

            onState(
                state,
            )

            if (items.isEmpty()) {
                val rawPreviewForUi =
                    manifestPreview(
                        manifestBody,
                        RAW_PREVIEW_UI_LIMIT,
                    )

                val diagnostic =
                    "media.config RAW " +
                        "(${manifestBody.length} chars): " +
                        rawPreviewForUi

                android.util.Log.w(
                    TAG,
                    "E1749 $diagnostic",
                )

                state =
                    state.copy(
                        detail =
                            "D7/7: media.config sin archivos compatibles",
                        lastError =
                            diagnostic,
                    )

                onState(
                    state,
                )

                throw IOException(
                    diagnostic,
                )
            }

            var completed =
                0

            for (item in items) {
                coroutineContext
                    .ensureActive()

                state =
                    state.copy(
                        detail =
                            "Descargando ${completed + 1}/${items.size}: ${item.fileName}",
                        lastError =
                            null,
                    )

                android.util.Log.i(
                    TAG,
                    state.detail,
                )

                onState(
                    state,
                )

                val file =
                    download(
                        baseUrl,
                        item,
                        onProgress,
                    )

                val imported =
                    try {
                        onFile(
                            item,
                            file,
                        )
                    } finally {
                        file.delete()
                    }

                if (!imported) {
                    throw IOException(
                        "Could not import ${item.fileName}",
                    )
                }

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

                android.util.Log.i(
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
                        "Sincronización completada",
                    lastError =
                        null,
                )

            android.util.Log.i(
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

            android.util.Log.e(
                TAG,
                "E1749 sync failed at [$failedAt]: $message",
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
                kotlinx.coroutines.NonCancellable,
            ) {
                hotspot.stop()

                temporaryDirectory
                    .deleteRecursively()
            }
        }
    }

    suspend fun fetchManifest(
        baseUrl: String,
    ): List<TuneBudsMediaItem> =
        withContext(
            Dispatchers.IO,
        ) {
            parseManifest(
                getText(
                    normalizeBaseUrl(
                        baseUrl,
                    ) +
                        "media.config",
                ),
            )
        }

    suspend fun downloadSingle(
        baseUrl: String,
        item: TuneBudsMediaItem,
    ): File =
        download(
            normalizeBaseUrl(
                baseUrl,
            ),
            item,
        ) { _, _, _ -> }

    fun parseManifest(
        body: String,
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
        baseUrl: String,
        item: TuneBudsMediaItem,
        onProgress:
            suspend (
                TuneBudsMediaItem,
                Long,
                Long,
            ) -> Unit,
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

                output.delete()

                val attempt =
                    index + 1

                android.util.Log.i(
                    TAG,
                    "RAW HTTP media download " +
                        "attempt $attempt/" +
                        "$MEDIA_DOWNLOAD_MAX_ATTEMPTS: " +
                        item.remoteName,
                )

                try {
                    val result =
                        rawHttpDownload(
                            mediaUrl,
                            output,
                            item,
                            onProgress,
                        )

                    lastAdvertisedLength =
                        result.advertisedLength

                    if (
                        result.readError != null
                    ) {
                        lastFailure =
                            result.readError

                        android.util.Log.w(
                            TAG,
                            "RAW HTTP stream closed with error " +
                                "after ${result.copied} bytes: " +
                                result.readError.message,
                        )
                    }

                    val valid =
                        validateDownloadedMedia(
                            item,
                            output,
                        )

                    android.util.Log.i(
                        TAG,
                        "RAW HTTP received=${output.length()}, " +
                            "advertised=${result.advertisedLength}, " +
                            "valid=$valid, " +
                            "file=${item.fileName}",
                    )

                    if (valid) {
                        android.util.Log.i(
                            TAG,
                            "Media accepted after RAW HTTP: " +
                                "${output.length()} bytes",
                        )

                        return@withContext output
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
                        Throwable,
                ) {
                    lastFailure =
                        error

                    android.util.Log.w(
                        TAG,
                        "RAW HTTP attempt $attempt failed: " +
                            (error.message
                                ?: error.javaClass.simpleName),
                        error,
                    )
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
                "TuneBuds RAW media download failed " +
                    "after $MEDIA_DOWNLOAD_MAX_ATTEMPTS attempts; " +
                    "last=${output.length()} bytes, " +
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
        urlString: String,
        output: File,
        item: TuneBudsMediaItem,
        onProgress:
            suspend (
                TuneBudsMediaItem,
                Long,
                Long,
            ) -> Unit,
    ): RawHttpDownloadResult {

        val url =
            URL(
                urlString,
            )

        if (
            !url.protocol.equals(
                "http",
                ignoreCase = true,
            )
        ) {
            throw IOException(
                "RAW TuneBuds transfer only supports HTTP",
            )
        }

        val port =
            if (
                url.port > 0
            ) {
                url.port
            } else {
                80
            }

        val path =
            if (
                url.file.isNullOrBlank()
            ) {
                "/"
            } else {
                url.file
            }

        val socket =
            Socket()

        try {
            socket.soTimeout =
                RAW_HTTP_READ_TIMEOUT_MS

            socket.connect(
                InetSocketAddress(
                    url.host,
                    port,
                ),
                RAW_HTTP_CONNECT_TIMEOUT_MS,
            )

            val networkOutput =
                BufferedOutputStream(
                    socket.getOutputStream(),
                )

            val hostHeader =
                if (
                    port == 80
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

                    append(
                        "\r\n",
                    )
                }

            networkOutput.write(
                request.toByteArray(
                    StandardCharsets.US_ASCII,
                ),
            )

            networkOutput.flush()

            val input =
                BufferedInputStream(
                    socket.getInputStream(),
                    128 * 1024,
                )

            val statusLine =
                readHttpLine(
                    input,
                )
                    ?: throw IOException(
                        "TuneBuds returned no HTTP status",
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
                statusCode !in 200..299
            ) {
                throw IOException(
                    "TuneBuds HTTP $statusCode for ${item.fileName}",
                )
            }

            val headers =
                mutableMapOf<
                    String,
                    String,
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
                    separator > 0
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

                    headers[name] =
                        value
                }
            }

            val transferEncoding =
                headers[
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
                    "TuneBuds returned chunked HTTP, unsupported in RAW diagnostic",
                )
            }

            val advertisedLength =
                headers[
                    "content-length"
                ]
                    ?.toLongOrNull()
                    ?: -1L

            android.util.Log.i(
                TAG,
                "RAW HTTP response=$statusCode, " +
                    "contentLength=$advertisedLength, " +
                    "connection=${headers["connection"]}, " +
                    "file=${item.fileName}",
            )

            var copied =
                0L

            var readError:
                IOException? = null

            output
                .outputStream()
                .buffered(
                    128 * 1024,
                )
                .use { target ->

                    val buffer =
                        ByteArray(
                            128 * 1024,
                        )

                    while (true) {
                        coroutineContext
                            .ensureActive()

                        val count =
                            try {
                                input.read(
                                    buffer,
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
                            count < 0
                        ) {
                            break
                        }

                        if (
                            count == 0
                        ) {
                            continue
                        }

                        target.write(
                            buffer,
                            0,
                            count,
                        )

                        copied +=
                            count

                        onProgress(
                            item,
                            copied,
                            advertisedLength,
                        )
                    }

                    target.flush()
                }

            return RawHttpDownloadResult(
                copied =
                    copied,
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

    private fun readHttpLine(
        input:
            BufferedInputStream,
    ): String? {

        val bytes =
            mutableListOf<
                Byte,
            >()

        var sawAnyByte =
            false

        while (true) {
            val value =
                input.read()

            if (
                value < 0
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
                value == '\n'.code
            ) {
                break
            }

            if (
                value != '\r'.code
            ) {
                bytes.add(
                    value.toByte(),
                )
            }

            if (
                bytes.size >
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
        item: TuneBudsMediaItem,
        file: File,
    ): Boolean {

        if (
            !file.exists() ||
            file.length() <= 0L
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
        file: File,
    ): Boolean {

        if (
            file.length() < 16L
        ) {
            return false
        }

        return runCatching {
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
                        fileLength - position <
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
                                    extended <= 0L
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

                    val end =
                        position +
                            boxSize

                    if (
                        end <
                        position ||
                        end >
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
                        end
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
    }

    private fun isCompleteJpeg(
        file: File,
    ): Boolean {

        if (
            file.length() < 4L
        ) {
            return false
        }

        return runCatching {
            RandomAccessFile(
                file,
                "r",
            ).use { raf ->

                val startA =
                    raf.readUnsignedByte()

                val startB =
                    raf.readUnsignedByte()

                raf.seek(
                    raf.length() -
                        2L,
                )

                val endA =
                    raf.readUnsignedByte()

                val endB =
                    raf.readUnsignedByte()

                startA == 0xFF &&
                    startB == 0xD8 &&
                    endA == 0xFF &&
                    endB == 0xD9
            }
        }
            .getOrDefault(
                false,
            )
    }

    private fun isPlausibleAudio(
        file: File,
    ): Boolean {

        if (
            file.length() < 12L
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

                prefix4 == "OggS" ||
                    prefix4 == "RIFF"
            }
        }
            .getOrDefault(
                false,
            )
    }

    private fun getText(
        url: String,
    ): String {

        android.util.Log.i(
            TAG,
            "HTTP GET $url",
        )

        val request =
            Request
                .Builder()
                .url(
                    url,
                )
                .header(
                    "Connection",
                    "close",
                )
                .header(
                    "User-Agent",
                    "TSClient/1.0",
                )
                .get()
                .build()

        CLIENT
            .newCall(
                request,
            )
            .execute()
            .use { response ->

                android.util.Log.i(
                    TAG,
                    "media.config HTTP response=${response.code}",
                )

                if (
                    !response.isSuccessful
                ) {
                    throw IOException(
                        "TuneBuds HTTP ${response.code} for media.config",
                    )
                }

                return response
                    .body
                    ?.string()
                    ?: throw IOException(
                        "TuneBuds returned an empty media.config",
                    )
            }
    }

    private fun manifestPreview(
        body: String,
        limit: Int,
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
            ) + "…"
        }
    }

    private fun stringsFromArray(
        array: JSONArray,
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
        value: String,
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
        value: String,
    ): String {

        val trimmed =
            value.trim()

        require(
            trimmed.startsWith(
                "http://",
            ) ||
                trimmed.startsWith(
                    "https://",
                ),
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
        path: String,
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
