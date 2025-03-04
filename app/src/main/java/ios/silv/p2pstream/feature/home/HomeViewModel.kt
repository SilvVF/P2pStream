package ios.silv.p2pstream.feature.home

import androidx.compose.ui.text.input.TextFieldValue
import com.zhuinden.simplestack.Backstack
import ios.silv.p2pstream.base.BaseViewModel
import ios.silv.p2pstream.base.mutate
import ios.silv.p2pstream.feature.call.CallKey
import ios.silv.p2pstream.net.Message
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val p2pManager: P2pManager,
    private val backstack: Backstack
): BaseViewModel() {

    private val _messages = MutableStateFlow(emptyList<Message.Text>())
    val messages = _messages.asStateFlow()

    private val _text = MutableStateFlow(TextFieldValue())
    val text = _text.asStateFlow()

    val clients = p2pManager.clientInfo().stateIn(scope, SharingStarted.Lazily, emptyList())

    init {
        p2pManager.textCh.onEach {
            _messages.mutate {
                add(it)
            }
        }
            .launchIn(scope)
    }

    fun sendMessage() {
        scope.launch {
            val message = _text.getAndUpdate { TextFieldValue() }
            p2pManager.outCh.send(message.text)
        }
    }

    fun changeText(textFieldValue: TextFieldValue) {
        _text.update { textFieldValue }
    }

    fun onCall() {
        backstack.goTo(CallKey)
    }
}