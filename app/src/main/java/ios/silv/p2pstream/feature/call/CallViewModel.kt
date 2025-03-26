package ios.silv.p2pstream.feature.call

import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import io.libp2p.core.PeerId
import ios.silv.p2pstream.dependency.DependencyAccessor
import ios.silv.p2pstream.dependency.commonDeps
import ios.silv.p2pstream.log.logcat
import ios.silv.p2pstream.net.CallState
import ios.silv.p2pstream.net.Message
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

val FRAME_CH = Channel<ByteArray>()
private var job: Job? = null

class CallViewModel @OptIn(DependencyAccessor::class) constructor(
    savedStateHandle: SavedStateHandle,
    private val p2pManager: P2pManager = commonDeps.p2pManager
): ViewModel() {

    val args = savedStateHandle.toRoute<CallScreen>()
    val currentState = MutableStateFlow(CallState.DISCONNECTED)

    private val decoder = MediaDecoder()

    init {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val controller = p2pManager.node.peers[PeerId.fromBase58(args.peerId)]!!.controller
                for (frame in FRAME_CH) {
                    logcat { "read from from frameCh size ${frame.size}" }
                    controller.stream(frame)
                }
            } finally {
                logcat { "frameCh closed" }
                awaitCancellation()
            }
        }
    }

    fun bind(surface: Surface) {
        decoder.initDecoder(surface)
    }

    fun unbind() {
        decoder.stop()
    }

    fun start() = viewModelScope.launch {
        supervisorScope {
            val p2pPeerId = PeerId.fromBase58(args.peerId)
            launch {
                p2pManager.callState.collect {
                    currentState.emit(it[p2pPeerId] ?: CallState.DISCONNECTED)
                }
            }

            launch(Dispatchers.IO) {
                p2pManager.message
                    .filterIsInstance<Message.Frame>()
                    .filter { msg -> msg.peerId == args.peerId }
                    .collect { frame ->
                        logcat { "viewmodel sampled frame from ${args.peerId}" }
                        decoder.decode(frame.data)
                    }
            }
        }
    }
}