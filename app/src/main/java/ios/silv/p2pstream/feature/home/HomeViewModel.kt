package ios.silv.p2pstream.feature.home

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.libp2p.core.PeerId
import ios.silv.p2pstream.base.AppNavigator
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.dependency.DependencyAccessor
import ios.silv.p2pstream.dependency.commonDeps
import ios.silv.p2pstream.feature.call.CallScreen
import ios.silv.p2pstream.net.CallState
import ios.silv.p2pstream.net.Message
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel @OptIn(DependencyAccessor::class) constructor(
    private val savedStateHandle: SavedStateHandle,
    private val p2pManager: P2pManager = commonDeps.p2pManager,
    private val navigator: AppNavigator = commonDeps.appNavigator
) : ViewModel() {

    private val _messages = MutableStateFlow(emptyList<Message.Text>())
    val messages = _messages.asStateFlow()

    private val _text = MutableStateFlow(TextFieldValue())
    val text = _text.asStateFlow()

    val clients = p2pManager.clientInfo().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        p2pManager.message.filterIsInstance<Message.Text>().onEach {
            _messages.mutate { add(it) }
        }
            .launchIn(viewModelScope)

        p2pManager.callState.onEach { state ->
            state.forEach { (id, callState) ->
                if (callState == CallState.ANSWER_OK) {
                    navigator.nav {
                        navigate(CallScreen(peerId = id.toBase58()))
                    }
                }
            }
        }
            .launchIn(viewModelScope)
    }

    fun startCall(peerId: PeerId) {
        p2pManager.node.callQueue.dial(peerId)
    }

    fun sendMessage() {
        viewModelScope.launch {
            val message = _text.getAndUpdate { TextFieldValue() }
            p2pManager.broadcast.send(message.text)
        }
    }

    fun changeText(textFieldValue: TextFieldValue) {
        _text.update { textFieldValue }
    }
}