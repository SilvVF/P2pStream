package ios.silv.p2pstream.net

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.wifi.WifiManager
import com.zhuinden.simplestack.ScopedServices
import io.libp2p.core.PeerId
import ios.silv.p2pstream.base.MutableStateFlowMap
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class P2pManager(
    context: Context,
    dispatcher: CoroutineDispatcher,
): ScopedServices.Registered {

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

        node.callQueue.dialing.collect {
            emit(it)
        }
    }

    fun getAlias(peerId: PeerId): String {
        return node.peers[peerId]?.name?.value.orEmpty()
    }

    private fun start() {
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

    private fun stop() {
        logcat { "stopping P2pManager" }
        node.stop()
        multicastLock.release()
    }

    override fun onServiceRegistered() = start()
    override fun onServiceUnregistered() = stop()
}