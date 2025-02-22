package ios.silv.p2pstream.net

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat.getSystemService
import com.zhuinden.simplestack.ScopedServices
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch


class P2pManager(
    context: Context,
    dispatcher: CoroutineDispatcher,
): ScopedServices.Registered {

    private lateinit var node: P2pNode
    private lateinit var multicastLock: WifiManager.MulticastLock
    private val initialized = Channel<Unit>(RENDEZVOUS)

    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("P2pManager"))

    private val wifi by lazy { context.getSystemService(WIFI_SERVICE) as WifiManager }

    private val _frameCh = MutableSharedFlow<Message.Frame>()
    val frameCh = _frameCh.asSharedFlow()
    private val _textCh = MutableSharedFlow<Message.Text>()
    val textCh = _textCh.asSharedFlow()

    private val output = Channel<String>()
    val outCh = output as SendChannel<String>

    private fun start() {
        logcat { "starting P2pManager" }
        scope.launch {
            multicastLock = wifi.createMulticastLock("libp2p-chatter")
            multicastLock.acquire()
            node = P2pNode(scope)
            initialized.close()

            launch {
                logcat { "listening for outgoing messages" }
                for (msg in output) {
                    node.send(msg)
                }
            }

            node.received.collect {
                logcat { "received incoming message $it" }
                when(it) {
                    is Message.Frame -> {
                        _frameCh.emit(it)
                    }
                    is Message.Text -> {
                        _textCh.emit(it)
                    }
                }
            }
        }
    }

    fun clientInfo() = flow {
        try {
            initialized.receive()
        } catch (e: ClosedReceiveChannelException){}

        node.clientInfo.collect {
            emit(it)
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