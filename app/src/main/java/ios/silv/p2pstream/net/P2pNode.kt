package ios.silv.p2pstream.net

import io.libp2p.core.Discoverer
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.discovery.MDnsDiscovery
import ios.silv.p2pstream.base.MutableStateFlowMap
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.math.log


private data class Client(
    val name: MutableStateFlow<String>,
    val controller: P2pController,
) {

    constructor(info: PeerInfo, conn: Pair<Stream, P2pController>): this(
        name = MutableStateFlow(info.peerId.toBase58()),
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

class P2pNode(private val scope: CoroutineScope) {

    var currentAlias: String
        private set
    private val peerFinder: Discoverer

    private val privateAddress: InetAddress = NetUtils.privateNetworkAddress()
    private val knownNodes = mutableSetOf<PeerId>()
    private val peers = MutableStateFlowMap<PeerId, Client>(emptyMap())
    val clientInfo = peers.map { value ->
        value.toList().map { (peerId, client) ->
            peerId to client.name
        }
    }

    private val _received = MutableSharedFlow<Message>()
    val received: SharedFlow<Message> = _received.asSharedFlow()

    private val innerHandler = object : P2pMessageHandler {

        private fun sendToListeners(message: Message) {
            scope.launch { _received.emit(message) }
        }

        override fun onFrame(peerId: PeerId, frame: ByteArray) {
            frameReceived(peerId, frame)
            sendToListeners(Message.Frame(peerId.toBase58(), frame))
        }

        override fun onMessage(peerId: PeerId, msg: String) {
            messageReceived(peerId, msg)
            sendToListeners(Message.Text(peerId.toBase58(), msg))
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
        currentAlias = p2pHost.peerId.toBase58()

        peerFinder = MDnsDiscovery(p2pHost, address = privateAddress)
        peerFinder.newPeerFoundListeners.add { peerFound(it) }
        peerFinder.start()
    }

    fun stop() {
        peerFinder.stop()
        p2pHost.stop()
    }

    private fun peerFound(info: PeerInfo) {
        logcat { "peer found $info" }
        if (
            info.peerId == p2pHost.peerId ||
            knownNodes.contains(info.peerId)
        ) {
            return
        }
        knownNodes.add(info.peerId)

        val conn = createClientController(info).getOrNull() ?: return
        conn.first.closeFuture().thenAccept { peerDisconnected(info) }

        peers[info.peerId] = Client(info, conn)
    }

    private fun frameReceived(id: PeerId, frame: ByteArray) {

    }

    suspend fun send(message: String, retransmit: Boolean = true) {
        logcat { "sending $message" }
        peers.value.forEach { (_, client) ->
            logcat { "sent to ${client.name.value}" }
            client.controller.send(message)
        }
        if (retransmit) {
            _received.emit(Message.Text(p2pHost.peerId.toBase58(), message))
        }

        if (message.startsWith("alias ")) {
            currentAlias = message.substring(6).trim()
        }
    }

    private fun messageReceived(id: PeerId, msg: String) {
        logcat { "received from: ${id.toBase58()} msg: $msg" }
        if (msg == "/who") {
            peers[id]?.controller?.send("alias $currentAlias")
        }

        if (msg.startsWith("alias ")) {
            val peer = peers[id] ?: return
            val previousAlias = peer.name
            val newAlias = msg.substring("alias ".length).trim()
            if (previousAlias.value != newAlias) {
                peer.name.value = newAlias
            }
            return
        }
        logcat { "${peers[id]?.name ?: id.toBase58()} > $msg" }
    }

    private fun peerDisconnected(info: PeerInfo) {
        logcat { "peerDisconnected ${info.peerId.toBase58()}" }
        peers.mutate { remove(info.peerId) }
        knownNodes.remove(info.peerId)
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