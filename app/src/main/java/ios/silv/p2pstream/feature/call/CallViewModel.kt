package ios.silv.p2pstream.feature.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.zhuinden.simplestack.Backstack
import io.libp2p.core.PeerId
import ios.silv.p2pstream.base.BaseViewModel
import ios.silv.p2pstream.log.logcat
import ios.silv.p2pstream.net.CallState
import ios.silv.p2pstream.net.Message
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CallViewModel(
    private val p2pManager: P2pManager,
    backstack: Backstack
): BaseViewModel() {

    val currentState = MutableStateFlow(CallState.DISCONNECTED)
    val sampledFrame = MutableStateFlow<Bitmap?>(null)

    val frameCh = Channel<ByteArray>()


    fun start(peerId: String) = scope.launch {
        launch {
            p2pManager.callState.collect {
                currentState.emit(it[PeerId.fromBase58(peerId)] ?: CallState.DISCONNECTED)
            }
        }
        launch {
            val chF = frameCh.receiveAsFlow().sample(100.milliseconds)
            chF.collect {
                p2pManager.node.sendTo(PeerId.fromBase58(peerId), it)
            }
        }

        p2pManager.message
            .filterIsInstance<Message.Frame>()
            .filter { msg -> msg.peerId == peerId }
            .sample(4.seconds)
            .collect { frame ->
                logcat { "viewmodel sampled frame from $peerId" }
                sampledFrame.update { prev ->
                    prev?.recycle()
                    BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                }
            }
    }
}