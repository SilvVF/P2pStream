package ios.silv.p2pstream.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackcomposeintegration.core.LocalBackstack
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.R
import ios.silv.p2pstream.base.ComposeKey
import ios.silv.p2pstream.net.P2pManager
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data object HomeKey: ComposeKey(){

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(HomeViewModel(lookup<P2pManager>(), backstack))
        }
    }

    @Composable
    override fun ScreenComposable(modifier: Modifier) {
        ComposeContent(modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeContent(modifier: Modifier) {
    val backstack = LocalBackstack.current
    val viewModel = remember { backstack.lookup<HomeViewModel>() }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    IconButton(
                        onClick = {},
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Call,
                            contentDescription = stringResource(R.string.call)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(
                bottom = paddingValues.calculateBottomPadding()
            )
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = paddingValues
            ) {
                item(key = "HEADER") {
                    val clients by viewModel.clients.collectAsStateWithLifecycle()

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface {
                            clients.fastForEach { (peerId, nameFlow) ->

                                val name by nameFlow.collectAsStateWithLifecycle()
                                val id = remember(peerId) { peerId.toBase58() }

                                ElevatedCard(Modifier.fillMaxWidth().clickable {
                                    viewModel.startCall(peerId)
                                }) {
                                    Text("id: $id")
                                    Text("alias: $name")
                                }
                            }
                        }
                    }
                }
                items(messages, key = { it.peerId + it.data }) {
                    Text("peer: ${it.peerId} data: ${it.data}")
                }
            }
            TextField(
                value = text,
                onValueChange = viewModel::changeText,
                trailingIcon = {
                    IconButton(
                        onClick = viewModel::sendMessage
                    ) {
                        Icon(Icons.AutoMirrored.Default.Send, null)
                    }
                },
                keyboardActions = KeyboardActions(
                    onDone = { viewModel.sendMessage() }
                )
            )
        }
    }
}