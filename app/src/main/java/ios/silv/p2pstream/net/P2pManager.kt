package ios.silv.p2pstream.net

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.wifi.WifiManager
import io.libp2p.core.PeerId
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class P2pManager(
    context: Context,
    dispatcher: CoroutineDispatcher,
) {

    private lateinit var multicastLock: WifiManager.MulticastLock
    private val initialized = Channel<Unit>(RENDEZVOUS)
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("P2pManager"))

    lateinit var node: P2pNode

    private val wifi by lazy { context.getSystemService(WIFI_SERVICE) as WifiManager }

    private val broadcastCh = Channel<String>()
    val broadcast: SendChannel<String> = broadcastCh

    private val _messages = MutableSharedFlow<Message>()
    val message: SharedFlow<Message> get() = _messages

    val callState = flow {

        emit(emptyMap())

        try {
            initialized.receive()
        } catch (_: ClosedReceiveChannelException){}

        node.callQueue.dialing.collect(::emit)
    }

    fun getAlias(peerId: PeerId): String {
        return node.peers[peerId]?.name?.value.orEmpty()
    }

    fun start() {
        logcat { "starting P2pManager" }
        scope.launch {
            multicastLock = wifi.createMulticastLock("libp2p-chatter")
            multicastLock.acquire()
            node = P2pNode(scope)
            initialized.close()

            launch {
                logcat { "listening for outgoing messages" }
                for (msg in broadcastCh) {
                    node.broadcast(msg)
                }
            }

            for (message in node.received) {
                _messages.emit(message)
            }
        }
    }

    fun clientInfo() = flow {

        emit(emptyList())

        try {
            initialized.receive()
        } catch (_: ClosedReceiveChannelException){}

        node.peers.collect {
            emit(it.map { (peerId, client) -> peerId to client.name })
        }
    }

    fun stop() {
        logcat { "stopping P2pManager" }
        node.stop()
        multicastLock.release()
    }
}