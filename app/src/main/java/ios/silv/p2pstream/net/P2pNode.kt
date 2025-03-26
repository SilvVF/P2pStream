package ios.silv.p2pstream.net

import android.telecom.Call
import androidx.compose.ui.util.trace
import io.libp2p.core.Discoverer
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.discovery.MDnsDiscovery
import ios.silv.p2pstream.base.MutableStateFlowMap
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.log.logcat
import ios.silv.p2pstream.net.CallState.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid


data class Client(
    val name: MutableStateFlow<String>,
    val controller: P2pController,
) {

    constructor(info: PeerInfo, conn: Pair<Stream, P2pController>): this(
        name = MutableStateFlow(""),
        controller = conn.second
    )
}

sealed interface Message {
    data class Text(val peerId: String, val data: String): Message
    data class Frame(val peerId: String, val data: ByteArray): Message {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Frame

            if (peerId != other.peerId) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }
        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

enum class CallState {
    DIALING,
    DIALED,
    ANSWER_OK,
    ANSWER_DECLINE,
    DISCONNECTED,
}

private const val CALL_TIMEOUT_MS = 10_000L

class CallQueue(
    private val node: P2pNode,
    private val scope: CoroutineScope,
) {
    val dialing = MutableStateFlowMap<PeerId, CallState>(emptyMap())

    fun onCall(peerId: PeerId) {
        dialing[peerId] = DIALED
        scope.launch {
            delay(CALL_TIMEOUT_MS)

            if ((dialing[peerId] ?: return@launch) <= DIALED) {
                dialing[peerId] = ANSWER_DECLINE
                node.sendTo(peerId, "DECLINE")
            }
        }
    }

    fun onAnswer(peerId: PeerId, ok: Boolean) {
        logcat { "setting call state $peerId, ok=$ok" }
        dialing[peerId] = if (ok) {
            ANSWER_OK
        } else {
            ANSWER_DECLINE
        }
    }

    fun dial(peerId: PeerId) = scope.launch {

        if (dialing[peerId] == DIALING) return@launch

        runCatching {
            dialing[peerId] = DIALING
            node.sendTo(peerId,"/call ")

            withTimeout(15.seconds) {
                dialing.map { it[peerId]!! }.transformWhile<CallState, Nothing> {
                    when(it) {
                        ANSWER_DECLINE -> error("declined")
                        ANSWER_OK -> false
                        else -> true
                    }
                }
                    .collect()
            }
        }

    }
}

class P2pNode(private val scope: CoroutineScope) {

    val callQueue = CallQueue(this, scope)

    private var currentAlias = ""
        set(value) {
            scope.launch {
                broadcast("/alias $value", loopback = false)
            }
            field = value
        }

    private val peerFinder: Discoverer

    private val privateAddress: InetAddress = NetUtils.privateNetworkAddress()
    val peers = MutableStateFlowMap<PeerId, Client>(emptyMap())

    private val _received = Channel<Message>(capacity = 20)
    val received: ReceiveChannel<Message> = _received

    private val innerHandler = object : P2pMessageHandler {

        private fun sendToListeners(message: Message) {
            _received.trySend(message)
        }

        override fun onFrame(peerId: PeerId, frame: ByteArray) {
            if (!handleControlFrame(peerId, frame)) {
                sendToListeners(Message.Frame(peerId.toBase58(), frame))
            }
        }

        override fun onMessage(peerId: PeerId, msg: String) {
            if (!handleControlMessage(peerId, msg)) {
                sendToListeners(Message.Text(peerId.toBase58(), msg))
            }
        }
    }

    private val p2pHost = host {
        protocols {
            +P2pBinding(P2pProtocol(scope, innerHandler))
        }
        network {
            // https://docs.libp2p.io/concepts/fundamentals/addressing/
            // session protocol/:address/transport protocol/port
            listen("/ip4/${privateAddress.hostAddress}/tcp/0")
        }
    }

    init {
        p2pHost.start().get()
        peerFinder = MDnsDiscovery(p2pHost, address = privateAddress)
        peerFinder.newPeerFoundListeners.add { peerFound(it) }
        peerFinder.start()

        scope.launch {
            while(true) {
                currentAlias = Random.nextInt().toString()
                delay(10_000)
            }
        }
    }

    fun declineCall(from: PeerId) {
        callQueue.onAnswer(from, false)
        sendTo(from, "/call DECLINE")
    }

    fun answerCall(from: PeerId) {
        callQueue.onAnswer(from, true)
        sendTo(from, "/call OK")
    }

    fun stop() {
        peerFinder.stop()
        p2pHost.stop()
    }

    private fun peerFound(info: PeerInfo) {
        logcat { "peer found $info" }
        if (
            info.peerId == p2pHost.peerId ||
            peers[info.peerId] != null
        ) {
            return
        }

        val conn = createClientController(info).getOrNull() ?: return
        conn.first.closeFuture().thenAccept { peerDisconnected(info) }

        peers[info.peerId] = Client(info, conn)
        sendTo(info.peerId, "/who")
    }

    private fun handleControlFrame(id: PeerId, frame: ByteArray): Boolean {
        return false
    }

    fun sendTo(peerId: PeerId, frame: ByteArray) {
        peers[peerId]?.controller?.stream(frame)
    }

    fun sendTo(peerId: PeerId, message: String) {
        peers[peerId]?.controller?.send(message)
    }

    suspend fun broadcast(message: String, loopback: Boolean = true) {
        logcat { "sending $message" }
        peers.value.forEach { (_, client) ->
            logcat { "sent to ${client.name.value}" }
            client.controller.send(message)
        }

        if (loopback) {
            _received.send(Message.Text(p2pHost.peerId.toBase58(), message))
        }
    }

    private fun handleControlMessage(id: PeerId, msg: String): Boolean {
        logcat { "received from: ${id.toBase58()} msg: $msg" }

        return when  {
            msg == "/who" -> {
                peers[id]?.controller?.send("/alias $currentAlias")
                true
            }
            msg.startsWith("/call", true) -> {
                val other = msg.removePrefix("/call").trim()
                when (other) {
                    "OK" -> callQueue.onAnswer(peerId = id, true)
                    "DECLINE" -> callQueue.onAnswer(peerId = id, false)
                    "" ->  callQueue.onCall(id)
                }
                true
            }
            msg.startsWith("/alias") -> {
                val client = peers[id] ?: return true
                val newAlias = msg.removePrefix("/alias").trim()
                client.name.value = newAlias
                true
            }
            else -> false
        }
    }

    private fun peerDisconnected(info: PeerInfo) {
        logcat { "peerDisconnected ${info.peerId.toBase58()}" }
        peers.mutate { remove(info.peerId) }
    }

    private fun createClientController(info: PeerInfo): Result<Pair<Stream, P2pController>> {
        return runCatching {
            val binding = P2pBinding(P2pProtocol(scope, innerHandler)).dial(
                p2pHost,
                info.peerId,
                info.addresses[0]
            )
            binding.stream.get() to binding.controller.get()
        }
    }
}