package ios.silv.p2pstream.net

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

interface P2pController {
    fun send(message: String)
    fun stream(frame: ByteArray)
}

// https://docs.libp2p.io/concepts/fundamentals/protocols/
/*
libp2p protocols have unique string identifiers, which are used
in the protocol negotiation process when connections are first opened.
By convention, protocol ids have a path-like structure,
with a version number as the final component:
 */
const val PROTOCOL_ID: ProtocolId = "/p2pstream/proto/0.0.1"

open class P2pBinding(protocol: P2pProtocol) :
    StrictProtocolBinding<P2pController>(PROTOCOL_ID, protocol)

interface P2pMessageHandler {
    fun onFrame(peerId: PeerId, frame: ByteArray)
    fun onMessage(peerId: PeerId, msg: String)
}

private const val MESSAGE = 1 shl 0
private const val FRAME = 1 shl 1

class P2pProtocol(
    private val scope: CoroutineScope,
    private val handler: P2pMessageHandler
) : ProtocolHandler<P2pController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    override fun onStartInitiator(stream: Stream) = onStart(stream)
    override fun onStartResponder(stream: Stream) = onStart(stream)

    private fun onStart(stream: Stream): CompletableFuture<P2pController> {
        val ready = Channel<Nothing>(RENDEZVOUS)
        logcat { "starting handler" }
        val handler = MessageHandler(ready)
        stream.pushHandler(handler)

        return scope.launch {
            try {
                ready.receive()
            } catch (e: ClosedReceiveChannelException) { }
        }.asCompletableFuture().thenApply {
            logcat { "resolving handler" }
            handler
        }
    }

    open inner class MessageHandler(
        private val deferred: Channel<Nothing>
    ) : ProtocolMessageHandler<ByteBuf>, P2pController {

        lateinit var stream: Stream

        override fun onActivated(stream: Stream) {
            this.stream = stream
            deferred.close()
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            logcat { "received onMessage ${stream.remotePeerId().toBase58()}" }
            val peerId = stream.remotePeerId()
            val (type, data) = decodeType(msg)
            when (type) {
                MESSAGE -> handler.onMessage(peerId, data.toString(Charset.defaultCharset()))
                FRAME -> handler.onFrame(peerId, data)
                else -> logcat {
                    "received bad msg from PeerID: ${
                        stream.remotePeerId().toBase58()
                    } ${data.toString(Charset.defaultCharset())}"
                }
            }
        }

        private fun decodeType(msg: ByteBuf): Pair<Int, ByteArray> {
            val type = msg.readInt()
            val data = msg.readBytes(msg.readableBytes())
            return Pair(
                type,
                data.array()
            )
        }

        private fun encodeType(type: Int, data: ByteArray): ByteBuf {
            val buf = ByteBuffer.allocate(Int.SIZE_BYTES + data.size).apply {
                putInt(type)
                put(data)
            }
            return buf.array().toByteBuf()
        }

        override fun send(message: String) {
            val data = message.toByteArray(Charset.defaultCharset())
            val send = encodeType(MESSAGE, data)
            logcat { "sending message bytes $message" }
            stream.writeAndFlush(send)
        }

        override fun stream(frame: ByteArray) {
            val send = encodeType(FRAME, frame)
            stream.writeAndFlush(send)
        }
    }
}