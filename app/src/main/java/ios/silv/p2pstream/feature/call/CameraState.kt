package ios.silv.p2pstream.feature.call

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ios.silv.p2pstream.base.MutableStateFlowList
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.log.LogPriority
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class CameraCapability(val camSelector: CameraSelector, val qualities: List<Quality>)

internal fun Quality.getNameString(): String {
    return when (this) {
        Quality.UHD -> "QUALITY_UHD(2160p)"
        Quality.FHD -> "QUALITY_FHD(1080p)"
        Quality.HD -> "QUALITY_HD(720p)"
        Quality.SD -> "QUALITY_SD(480p)"
        else -> throw IllegalArgumentException("Quality $this is NOT supported")
    }
}

internal fun Quality.getAspectRatioStrategy(): AspectRatioStrategy {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    return when {
        hdQualities.contains(this) -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        this == Quality.SD -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    }
}

internal fun Quality.getAspectRatioString(portraitMode: Boolean): String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val ratio = when {
        hdQualities.contains(this) -> Pair(16, 9)
        this == Quality.SD -> Pair(4, 3)
        else -> throw UnsupportedOperationException()
    }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
    else "H,${ratio.first}:${ratio.second}"
}


suspend fun loadCameraCapabilities(
    lifecycleOwner: LifecycleOwner,
    context: Context,
    loaded: (CameraCapability) -> Unit
) {
    try {
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.unbindAll()

        for (camSelector in arrayOf(
            CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA
        )) {
            try {
                // just get the camera.cameraInfo to query capabilities
                // we are not binding anything here.
                if (provider.hasCamera(camSelector)) {
                    val camera = provider.bindToLifecycle(lifecycleOwner, camSelector)
                    val capabilities = Recorder.getVideoCapabilities(camera.cameraInfo)

                    capabilities.getSupportedQualities(DynamicRange.UNSPECIFIED).filter { quality ->
                        listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).contains(quality)
                    }.also {
                        loaded(CameraCapability(camSelector, it))
                    }
                }
            } catch (e: Exception) { }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { }
}

sealed interface CameraUiEvent {
    data class ChangeQuality(val idx: Int) : CameraUiEvent
    data class ChangeCamera(val idx: Int) : CameraUiEvent
}


//private val NoOpChecker = object : ConnectChecker {
//    override fun onAuthError() {}
//    override fun onAuthSuccess() {}
//    override fun onConnectionFailed(reason: String) {}
//    override fun onConnectionStarted(url: String) {}
//    override fun onConnectionSuccess() {}
//    override fun onDisconnect() {}
//}

class CameraStateHolder internal constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    frameCh: SendChannel<ByteArray>
) {

    data class State(
        val cameraIdx: Int = 0,
        val capabilities: List<CameraCapability> = emptyList(),
        val qualityIdx: Int = 0,
        val qualities: List<Quality> = emptyList(),
        val uiEnabled: Boolean = false,
        val handleEvent: (event: CameraUiEvent) -> Unit
    )

    private val lifecycleScope: LifecycleCoroutineScope = lifecycleOwner.lifecycleScope
    private lateinit var captureView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider

    private val mediaCoder = MediaCoder(frameCh)

    private var deferredCapabilities: Deferred<Unit>? = null

    private val cameraIndex = MutableStateFlow(0)
    private val qualityIndex = MutableStateFlow(0)
    private val uiEnabled = MutableStateFlow(false)

    private val cameraCapabilities = MutableStateFlowList<CameraCapability>(emptyList())

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(context) }

    val state = combine(
        cameraIndex.asStateFlow(),
        cameraCapabilities.asStateFlow(),
        qualityIndex.asStateFlow(),
        uiEnabled.asStateFlow()
    ) { cameraIdx, capabilities, qualityIdx, enabled ->
        State(
            cameraIdx,
            capabilities,
            qualityIdx,
            capabilities.getOrNull(cameraIdx)?.qualities.orEmpty(),
            enabled
        ) { event ->
            handleEvent(event)
        }
    }
        .stateIn(
            lifecycleScope,
            SharingStarted.WhileSubscribed(5_000),
            State { event -> handleEvent(event) }
        )

    init {
        deferredCapabilities = lifecycleScope.async {
            cameraCapabilities.mutate { clear() }
            loadCameraCapabilities(lifecycleOwner, context) { capabilities ->
                logcat { "$capabilities" }
                cameraCapabilities.mutate {
                    add(capabilities)
                }
            }
        }
        lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                mediaCoder.start()

                if (deferredCapabilities != null) {
                    deferredCapabilities!!.await()
                    deferredCapabilities = null
                }

                try {
                    awaitCancellation()
                } finally {
                    logcat { "stopping coder" }
                    mediaCoder.stop()
                }
            }
        }
    }

    suspend fun bindCaptureUseCase(previewView: PreviewView) = runCatching {
        logcat { "running bindCaptureUseCase" }
        this.captureView = previewView

        deferredCapabilities?.await()
        val capability = cameraCapabilities[cameraIndex.value]

        cameraProvider = ProcessCameraProvider.awaitInstance(context)
        val cameraSelector = capability.camSelector

        val quality = capability.qualities[qualityIndex.value]
        val qualitySelector = QualitySelector.from(quality)
        logcat { qualitySelector.toString() }
        logcat { cameraSelector.toString() }

        val preview = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(
                quality.getAspectRatioStrategy()
            ).build()
        ).build()
            .apply {
                logcat { "Set surfaceProvider" }
                surfaceProvider = previewView.surfaceProvider
            }

        // https://developer.android.com/media/camera/camerax/analyze#operating-modes
        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(2))

        analyzer.setAnalyzer(mainThreadExecutor) { imageProxy ->
            logcat(LogPriority.VERBOSE) { "sending frame" }
            try {
                val b = imageProxy.toNV21()
                scope.launch(NonCancellable) {
                    runCatching {
                        mediaCoder.decode(b)
                    }.onFailure { t ->
                        logcat { t.stackTraceToString() }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { e.stackTraceToString() }
            } finally {
                imageProxy.close()
            }
        }

        try {
            logcat { "unbinding previous and binding to lifecycle" }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, analyzer
            )
            logcat { "unbound previous and bound to lifecycle" }
        } catch (e: Exception) {
            logcat { "Use case binding failed ${e.stackTraceToString()}" }
            resetUiState()
        }
        uiEnabled.emit(true)
    }

    private fun resetUiState() {
        uiEnabled.value = true
        cameraIndex.value = 0
        qualityIndex.value = 0
    }

    fun handleEvent(event: CameraUiEvent) {
        logcat { "handling event $event" }
        when (event) {
            is CameraUiEvent.ChangeQuality -> {
                if (event.idx == qualityIndex.value || !uiEnabled.value) return

                qualityIndex.value = event.idx
                uiEnabled.value = false

                lifecycleScope.launch {
                    bindCaptureUseCase(captureView)
                }
            }

            is CameraUiEvent.ChangeCamera -> {
                if (event.idx == cameraIndex.value || !uiEnabled.value) return

                cameraIndex.value = event.idx
                uiEnabled.value = false

                lifecycleScope.launch {
                    bindCaptureUseCase(captureView)
                }
            }
        }
    }
}
