    private fun startTuneBudsMediaSync() {
        if (tuneBudsMediaJob?.isActive == true) return

        if (!hasBluetooth(this) || !hasWifiP2pPermission(this)) {
            ensureGlassesTransportPermissions("TuneBuds media sync") {
                startTuneBudsMediaSync()
            }
            return
        }

        val manager = getOrCreateTuneBudsManager()

        if (!manager.isConnected()) {
            Toast.makeText(
                this,
                "Connect TuneBuds glasses first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(
                this,
                "Another glasses session is already active.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val lease =
            acquireExclusiveGlassesSession(
                GlassesSession.MEDIA_SYNC,
            ) ?: return

        mediaSessionLease = lease
        tuneBudsMediaCancelled = false

        resetTransferUiState()
        setTransferUiVisible(true)
        setTransferFlowLabel(
            GlassesSyncFlow.CUSTOM,
        )
        setTransferDetail(
            "Starting TuneBuds media sync...",
        )

        val hotspot =
            TuneBudsLocalHotspot(this)

        tuneBudsMediaHotspot =
            hotspot

        val temporaryDirectory =
            File(
                cacheDir,
                "tunebuds_media_${System.currentTimeMillis()}",
            )

        tuneBudsMediaJob =
            lifecycleScope.launch(
                Dispatchers.IO,
            ) {

                var result:
                    Result<Int>? = null

                try {

                    val sync =
                        TuneBudsMediaSync(
                            manager,
                            hotspot,
                            temporaryDirectory,
                        )

                    result =
                        sync.sync(

                            onState = { syncState ->

                                withContext(
                                    Dispatchers.Main,
                                ) {

                                    if (
                                        tuneBudsMediaCancelled
                                    ) {
                                        return@withContext
                                    }

                                    if (
                                        syncState.total > 0
                                    ) {
                                        transferTotalJpg =
                                            syncState.total

                                        transferTotalMp4 =
                                            0

                                        transferTotalOpus =
                                            0

                                        transferDoneJpg =
                                            syncState.completed

                                        transferDoneMp4 =
                                            0

                                        transferDoneOpus =
                                            0
                                    }

                                    renderTransferProgress()

                                    setTransferDetail(
                                        syncState.lastError
                                            ?.let {
                                                "${syncState.detail}: $it"
                                            }
                                            ?: syncState.detail,
                                    )
                                }
                            },

                            onProgress = {
                                item,
                                downloaded,
                                total,
                                ->

                                if (
                                    total > 0L
                                ) {

                                    withContext(
                                        Dispatchers.Main,
                                    ) {

                                        if (
                                            !tuneBudsMediaCancelled
                                        ) {

                                            setTransferDetail(
                                                "Downloading ${item.fileName}: " +
                                                    "${downloaded / 1024} / " +
                                                    "${total / 1024} KB",
                                            )
                                        }
                                    }
                                }
                            },

                            onFile = {
                                item,
                                file,
                                ->

                                importVendorMediaFile(
                                    file,
                                    VendorMediaItem(
                                        fileName =
                                            item.fileName,

                                        type =
                                            when (
                                                item.type
                                            ) {

                                                TuneBudsMediaType.PHOTO ->
                                                    VendorMediaType.PHOTO

                                                TuneBudsMediaType.VIDEO ->
                                                    VendorMediaType.VIDEO

                                                TuneBudsMediaType.AUDIO ->
                                                    VendorMediaType.AUDIO
                                            },
                                    ),
                                )
                            },
                        )

                } finally {

                    withContext(
                        Dispatchers.Main +
                            kotlinx.coroutines.NonCancellable,
                    ) {

                        val completed =
                            result?.getOrNull()

                        val error =
                            result
                                ?.exceptionOrNull()

                        val cancelled =
                            tuneBudsMediaCancelled

                        tuneBudsMediaJob =
                            null

                        tuneBudsMediaHotspot =
                            null

                        setTransferUiVisible(
                            false,
                        )

                        releaseExclusiveGlassesSession(
                            lease,
                        )

                        mediaSessionLease =
                            null

                        if (
                            !cancelled
                        ) {

                            val message =
                                when {

                                    completed != null -> {
                                        "TuneBuds sync complete: $completed files"
                                    }

                                    error != null -> {
                                        "TuneBuds sync FAILED: " +
                                            (
                                                error.message
                                                    ?: error.javaClass.simpleName
                                            )
                                    }

                                    else -> {
                                        "TuneBuds media sync failed"
                                    }
                                }

                            Log.e(
                                "TuneBudsMediaSync",
                                message,
                                error,
                            )

                            Toast.makeText(
                                this@MainActivity,
                                message,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
    }
