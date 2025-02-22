package ios.silv.p2pstream.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhuinden.simplestack.Backstack
import ios.silv.p2pstream.base.ComposeFragment

class HomeFragment: ComposeFragment() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun FragmentComposable(backstack: Backstack) {

        val viewModel = backStackViewModel<HomeViewModel>()

        val messages by viewModel.messages.collectAsStateWithLifecycle()
        val text by viewModel.text.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Home") },
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
                    item {
                        val clients by viewModel.clients.collectAsStateWithLifecycle()

                        Column {
                            clients.fastForEach {
                                val name by it.second.collectAsStateWithLifecycle()
                                val id = remember { it.first.toBase58() }
                                Text("$id $name")
                            }
                        }
                    }
                    items(messages, key = { it.peerId + it.data }) {
                        Text("peer: ${it.peerId} data: ${it.data}")
                    }
                }
                TextField(
                    value = text,
                    modifier = Modifier
                        .imePadding(),
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
}