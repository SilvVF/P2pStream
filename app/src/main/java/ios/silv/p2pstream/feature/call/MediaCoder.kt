package ios.silv.p2pstream.feature.call

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CONFIGURE_FLAG_ENCODE
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import androidx.camera.core.ImageProxy
import ios.silv.p2pstream.log.LogPriority
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

private const val CODEC_TIMEOUT_US = 100_000L
private val defaultMediaFormatBuilder: MediaFormat.() -> Unit = {
    setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    )
    setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
}

private const val WIDTH = 640
private const val HEIGHT = 480
private const val vBitrate = 1200 * 1000
private const val sampleRate = 32000
private const val isStereo = true
private const val aBitrate = 128 * 1000

class MediaDecoder {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isConfigured = false

    fun initDecoder(surface: Surface) {
        this.surface = surface
        codec = MediaCodec.createDecoderByType("video/avc")
    }

    private fun configureDecoder(data: ByteArray) {
        val spsPps = extractSpsPps(data) // Extract SPS/PPS if needed
        if (spsPps != null && surface != null) {
            val (width, height) = parseSps(spsPps.sps)
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsPps.sps)) // SPS
            format.setByteBuffer("csd-1", ByteBuffer.wrap(spsPps.pps)) // PPS
            codec?.configure(format, surface, null, 0)
            codec?.start()
            isConfigured = true
        }
    }

    fun decode(data: ByteArray) {
        if (!isConfigured) {
            configureDecoder(data) // Configure the decoder with SPS/PPS
            return
        }

        codec?.let { codec ->
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)

            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true) // Render to Surface
                outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            }
        }
    }

    fun stop() {
        try {
            codec?.flush()
            codec?.stop()
            codec?.release()
        } catch (ignored: Exception) {
            logcat { ignored.stackTraceToString() }
        } finally {
            surface = null
            codec = null
        }
    }

    data class SpsPps(val sps: ByteArray, val pps: ByteArray)

    fun extractSpsPps(data: ByteArray): SpsPps? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        var offset = 0
        while (offset < data.size - 4) {
            if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
            ) {
                val nalType = data[offset + 4].toInt() and 0x1F // Extract NAL type

                when (nalType) {
                    7 -> { // SPS
                        val end = findNalUnitEnd(data, offset)
                        sps = data.copyOfRange(offset, end)
                    }

                    8 -> { // PPS
                        val end = findNalUnitEnd(data, offset)
                        pps = data.copyOfRange(offset, end)
                    }
                }

                if (sps != null && pps != null) {
                    return SpsPps(sps, pps) // Return as soon as both are found
                }
            }
            offset++
        }
        return null
    }

    // Helper function to find the next start code
    private fun findNalUnitEnd(data: ByteArray, start: Int): Int {
        var offset = start + 4
        while (offset < data.size - 4) {
            if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()
            ) {
                return offset
            }
            offset++
        }
        return data.size
    }
}

class MediaCoder(
    private val encoded: SendChannel<ByteArray>
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = BufferInfo()
    private var rotation = 0

    var dropped = 0L
    var ok = 0L

    private val mediaFormat: MediaFormat = MediaFormat().apply {
        if (rotation == 0 || rotation == 180) {
            setInteger(MediaFormat.KEY_WIDTH, WIDTH)
            setInteger(MediaFormat.KEY_HEIGHT, HEIGHT)
        } else {
            setInteger(MediaFormat.KEY_WIDTH, HEIGHT)
            setInteger(MediaFormat.KEY_HEIGHT, WIDTH)
        }

        setString(MediaFormat.KEY_MIME, "video/avc") // Ensure MIME is set correctly
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Force a keyframe every 1 second
        setInteger(MediaFormat.KEY_BIT_RATE, vBitrate) // Adjust bitrate as needed
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        ) // Important!
    }

    val mutex = Mutex()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (codec == null) {
            codec = MediaCodec.createDecoderByType("video/avc").apply {
                configure(mediaFormat, null, null, CONFIGURE_FLAG_ENCODE)
                start()
            }
        }
    }

    fun stop() {
        try {
            codec?.flush()
            codec?.stop()
            codec?.release()
        } catch (ignored: Exception) {
            logcat { ignored.stackTraceToString() }
        } finally {
            job?.cancel()
            codec = null
        }
    }

    suspend fun decode(data: ByteArray) {
        val codec = requireNotNull(codec)

        val inIndex = codec.dequeueInputBuffer(-1)
        require(inIndex >= 0)

        logcat(LogPriority.VERBOSE) { "queueing input" }

        val input = codec.getInputBuffer(inIndex)!!
        input.clear()
        input.put(data)

        codec.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)

        mutex.withLock {

            if (job?.isActive == true) {
                logcat { "Job already running" }
                return
            }

            job = scope.launch {
                try {
                    while (true) {
                        when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, -1)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                break
                            }

                            in 0..Int.MAX_VALUE -> {
                                codec.getOutputBuffer(outIndex)?.let { buf ->
                                    val frameData = ByteArray(buf.remaining())
                                    buf.get(frameData)

                                    logcat { "Sending frame $ok" }
                                    encoded.send(frameData)
                                    ok++
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                            }

                            else -> {
                                logcat { "Unexpected output state: $outIndex" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    dropped++
                    logcat { "dropped frame $dropped" }
                    logcat { e.stackTraceToString() }
                } finally {
                    job = null
                    cancel()
                }
            }
        }
    }
}

class BitReader(private val data: ByteArray) {
    private var bitOffset = 0
    private var byteOffset = 0

    fun readBits(numBits: Int): Int {
        var result = 0
        for (i in 0 until numBits) {
            if (byteOffset >= data.size) return result // Prevent out-of-bounds
            result = (result shl 1) or ((data[byteOffset].toInt() shr (7 - bitOffset)) and 1)
            bitOffset++
            if (bitOffset == 8) {
                bitOffset = 0
                byteOffset++
            }
        }
        return result
    }

    fun readUE(): Int {
        var leadingZeroBits = 0
        while (readBits(1) == 0) {
            leadingZeroBits++
        }
        return (1 shl leadingZeroBits) - 1 + readBits(leadingZeroBits)
    }

    fun readSE(): Int {
        val value = readUE()
        return if (value % 2 == 0) -(value / 2) else (value + 1) / 2
    }

    fun skipScalingList(size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = readSE()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }
}


fun parseSps(sps: ByteArray): Pair<Int, Int> {
    val bitReader = BitReader(sps)

    // Skip NAL header (1 byte)
    bitReader.readBits(8)

    // Profile, constraints, and level (3 bytes)
    bitReader.readBits(8) // profile_idc
    bitReader.readBits(8) // constraint_set_flags + reserved_zero_2bits
    bitReader.readBits(8) // level_idc

    // seq_parameter_set_id (UE)
    bitReader.readUE()

    // If high profile, skip chroma_format_idc, bit_depth, etc.
    val profileIdc = sps[1].toInt() and 0xFF
    if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
        val chromaFormatIdc = bitReader.readUE()
        if (chromaFormatIdc == 3) bitReader.readBits(1) // separate_color_plane_flag
        bitReader.readUE() // bit_depth_luma_minus8
        bitReader.readUE() // bit_depth_chroma_minus8
        bitReader.readBits(1) // qpprime_y_zero_transform_bypass_flag
        val seqScalingMatrixPresentFlag = bitReader.readBits(1)
        if (seqScalingMatrixPresentFlag == 1) {
            for (i in 0 until (if (chromaFormatIdc == 3) 12 else 8)) {
                if (bitReader.readBits(1) == 1) { // seq_scaling_list_present_flag
                    // Skip scaling list (not needed for width/height)
                    if (i < 6) bitReader.skipScalingList(16) else bitReader.skipScalingList(64)
                }
            }
        }
    }

    // log2_max_frame_num_minus4 (UE)
    bitReader.readUE()

    // pic_order_cnt_type (UE)
    val picOrderCntType = bitReader.readUE()
    if (picOrderCntType == 0) {
        bitReader.readUE() // log2_max_pic_order_cnt_lsb_minus4
    } else if (picOrderCntType == 1) {
        bitReader.readBits(1) // delta_pic_order_always_zero_flag
        bitReader.readSE() // offset_for_non_ref_pic
        bitReader.readSE() // offset_for_top_to_bottom_field
        val numRefFramesInPicOrderCntCycle = bitReader.readUE()
        for (i in 0 until numRefFramesInPicOrderCntCycle) {
            bitReader.readSE() // offset_for_ref_frame[i]
        }
    }

    // num_ref_frames (UE)
    bitReader.readUE()

    // gaps_in_frame_num_value_allowed_flag (1 bit)
    bitReader.readBits(1)

    // Width & Height Calculation
    val picWidthInMbsMinus1 = bitReader.readUE()
    val picHeightInMapUnitsMinus1 = bitReader.readUE()

    val frameMbsOnlyFlag = bitReader.readBits(1)
    val picHeightInMbs = (picHeightInMapUnitsMinus1 + 1) * (if (frameMbsOnlyFlag == 1) 1 else 2)

    // Frame cropping
    val frameCroppingFlag = bitReader.readBits(1)
    var cropLeft = 0
    var cropRight = 0
    var cropTop = 0
    var cropBottom = 0

    if (frameCroppingFlag == 1) {
        cropLeft = bitReader.readUE()
        cropRight = bitReader.readUE()
        cropTop = bitReader.readUE()
        cropBottom = bitReader.readUE()
    }

    // Calculate final width & height
    val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
    val height = picHeightInMbs * 16 - (cropTop + cropBottom) * 2

    return Pair(width, height)
}


// https://en.wikipedia.org/wiki/Y%E2%80%B2UV
fun ImageProxy.toNV21(): ByteArray {
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
