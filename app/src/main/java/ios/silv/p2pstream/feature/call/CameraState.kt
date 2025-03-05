package ios.silv.p2pstream.feature.call

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CONFIGURE_FLAG_ENCODE
import android.media.MediaCodec.INFO_TRY_AGAIN_LATER
import android.media.MediaCodec.createEncoderByType
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.view.PreviewView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.pedro.common.ConnectChecker
import com.pedro.common.toByteArray
import com.pedro.encoder.BaseEncoder
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.extrasources.BufferDecoder
import com.pedro.extrasources.BufferVideoSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.streamclient.GenericStreamClient
import com.pedro.library.util.streamclient.RtspStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtsp.rtsp.RtspClient
import ios.silv.p2pstream.base.MutableStateFlowList
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.log.LogPriority
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
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


private fun CoroutineScope.loadCameraCapabilities(
    lifecycleOwner: LifecycleOwner,
    context: Context,
    loaded: (CameraCapability) -> Unit
): Deferred<Unit> = async {
    try {

        logcat { "getting provider" }
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.unbindAll()

        logcat { "got provider and unbound usecases" }

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
                        logcat { "loaded cam selector" }
                        loaded(CameraCapability(camSelector, it))
                    }
                }
            } catch (e: Exception) {
                logcat { "Camera Face $camSelector is not supported" }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
    }
}

sealed interface CameraUiEvent {
    data class ChangeQuality(val idx: Int) : CameraUiEvent
    data class ChangeCamera(val idx: Int) : CameraUiEvent
}

private const val VIDEO_AVC = "video/avc"
private const val CODEC_TIMEOUT_US = 10000L
private val defaultMediaFormatBuilder: MediaFormat.() -> Unit = {
    setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    )
    setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
}

// https://en.wikipedia.org/wiki/Y%E2%80%B2UV
private fun ImageProxy.toNV21(): ByteArray {
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer

    val ySize = yPlane.remaining()
    val uvSize = uPlane.remaining() + vPlane.remaining()

    val nv21 = ByteArray(ySize + uvSize)

    yPlane.get(nv21, 0, ySize)
    vPlane.get(nv21, ySize, vPlane.remaining()) // V
    uPlane.get(nv21, ySize + vPlane.remaining(), uPlane.remaining()) // U

    return nv21
}

class BufferDecoderNotP(private val outCh: SendChannel<ByteArray>) {

    private var codec: MediaCodec? = null
    private var mediaFormat: MediaFormat? = null
    private var startTs = 0L
    private val bufferInfo = BufferInfo()
    private val client = RtspClient(NoOpChecker)

    private val executor = Executors.newSingleThreadExecutor()

    fun prepare(width: Int, height: Int, fps: Int, rotation: Int, mime: String) {
        val mediaFormat = MediaFormat()
        if (rotation == 0 || rotation == 180) {
            mediaFormat.setInteger(MediaFormat.KEY_WIDTH, width)
            mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, height)
        } else {
            mediaFormat.setInteger(MediaFormat.KEY_WIDTH, height)
            mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, width)
        }
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        mediaFormat.setString(MediaFormat.KEY_MIME, mime)
        this.mediaFormat = mediaFormat
    }

    fun start() {
        mediaFormat?.let {
            startTs = System.nanoTime() / 1000
            codec = MediaCodec.createDecoderByType(CodecUtil.H264_MIME)
            codec?.start()
        }
    }

    fun stop() {
        try {
            codec?.flush()
            codec?.stop()
            codec?.release()
        } catch (ignored: Exception) { } finally {
            codec = null
            mediaFormat = null
            startTs = 0
        }
    }

    fun decode(data: ByteArray) {
        codec?.let {
            val inIndex = it.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val input = it.getInputBuffer(inIndex)
                input?.put(data)
                it.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000 - startTs, 0)
            }
            executor.execute {
                try {
                    val outIndex = it.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outIndex >= 0) {
                        val out = it.getOutputBuffer(outIndex)
                        runCatching {
                            outCh.trySend(out!!.array())
                            client.sendVideo(out, bufferInfo)
                        }
                        it.releaseOutputBuffer(outIndex, System.nanoTime() / 1000 - startTs)
                    }
                } catch (e: Exception) {
                    logcat { e.stackTraceToString() }
                }
            }
        }
    }
}

private val NoOpChecker = object : ConnectChecker {
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onDisconnect() {}
}

class CameraStateHolder internal constructor(
    private val context: Context,
    internal val lifecycleOwner: LifecycleOwner,
    private val frameCh: SendChannel<ByteArray>
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

    var deferredCapabilities: Deferred<Unit>? = null

    private val cameraIndex = MutableStateFlow(0)
    private val qualityIndex = MutableStateFlow(0)
    private val uiEnabled = MutableStateFlow(false)

    private val cameraCapabilities = MutableStateFlowList<CameraCapability>(emptyList())

    val width = 640
    val height = 480
    val vBitrate = 1200 * 1000
    private var rotation = 0
    private val sampleRate = 32000
    private val isStereo = true
    private val aBitrate = 128 * 1000

    val decoder = BufferDecoderNotP(frameCh)

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
        lifecycleScope.launch {
            loadCameraCapabilities(lifecycleOwner, context) { capabilities ->
                cameraCapabilities.mutate {
                    add(capabilities)
                }
            }
                .await()
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

        analyzer.setAnalyzer(mainThreadExecutor) { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            logcat(LogPriority.VERBOSE) { "received a frame h:${imageProxy.height}, w:${imageProxy.width}, r:${rotationDegrees}" }
            decoder.decode(imageProxy.toNV21())
            imageProxy.close()
        }

        try {
            logcat { "unbinding previous and binding to lifecycle" }
            decoder.stop()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, analyzer
            )
            decoder.start()
            logcat { "unbound previous and bound to lifecycle" }
        } catch (e: Exception) {
            logcat { "Use case binding failed ${e.stackTraceToString()}" }
            decoder.stop()
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
