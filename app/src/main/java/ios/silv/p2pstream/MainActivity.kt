package ios.silv.p2pstream

import android.os.Bundle
import android.view.Display
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarResult.*
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhuinden.simplestack.AsyncStateChanger
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.History
import com.zhuinden.simplestackcomposeintegration.core.BackstackProvider
import com.zhuinden.simplestackcomposeintegration.core.ComposeNavigator
import com.zhuinden.simplestackcomposeintegration.core.ComposeNavigatorInitializer
import com.zhuinden.simplestackcomposeintegration.core.ComposeStateChanger
import com.zhuinden.simplestackcomposeintegration.core.LocalBackstack
import com.zhuinden.simplestackcomposeintegration.core.rememberBackstack
import com.zhuinden.simplestackextensions.navigatorktx.backstack
import com.zhuinden.simplestackextensions.servicesktx.lookup
import io.libp2p.core.PeerId
import ios.silv.p2pstream.base.ServiceProvider
import ios.silv.p2pstream.feature.home.HomeKey
import ios.silv.p2pstream.log.logcat
import ios.silv.p2pstream.net.CallState
import ios.silv.p2pstream.net.CallState.*
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val snackbarState = SnackbarHostState()

        setContent {
            MaterialTheme {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarState) }
                ) { paddingValues ->
                    Box(Modifier.padding(paddingValues)) {
                        ComposeNavigator(
                            init = {
                                createBackstack(
                                    initialKeys = History.of(HomeKey),
                                    scopedServices = ServiceProvider(),
                                    globalServices = (application as App).globalServices
                                )
                            }
                        ) {
                            DisplayCallSnackBars(snackbarState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeNavigator(
    modifier: Modifier = Modifier,
    animationConfiguration: ComposeStateChanger.AnimationConfiguration =
        ComposeStateChanger.AnimationConfiguration(),
    id: String = "DEFAULT_SINGLE_COMPOSE_STACK_IDENTIFIER",
    interceptBackButton: Boolean = true,
    init: ComposeNavigatorInitializer.() -> Backstack,
    content: @Composable () -> Unit,
) {
    val composeStateChanger =
        remember(id, animationConfiguration) { ComposeStateChanger(animationConfiguration) }
    val asyncStateChanger =
        remember(id, composeStateChanger) { AsyncStateChanger(composeStateChanger) }

    val backstack = rememberBackstack(asyncStateChanger, id, interceptBackButton, init)

    BackstackProvider(backstack) {
        composeStateChanger.RenderScreen(modifier)
        content()
    }
}


@Composable
private fun DisplayCallSnackBars(
    snackbarState: SnackbarHostState
) {
    val lifecycle = LocalLifecycleOwner.current
    val backstack = LocalBackstack.current
    val p2pManager = remember(backstack) { backstack.lookup<P2pManager>() }
    val currentSnackBars = remember { mutableMapOf<PeerId, Job>() }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            logcat { "listening for call state" }
            p2pManager.callState.collect { state ->
                logcat { state.toString() }
                state.onEach { (peerId, callState) ->
                    when (callState) {
                        DIALED -> {
                            if (currentSnackBars[peerId]?.isActive != true) {
                                launch {
                                    val action = snackbarState.showSnackbar(
                                        "${p2pManager.getAlias(peerId)} dialed...",
                                        actionLabel = "Accept",
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Indefinite
                                    )
                                    when (action) {
                                        Dismissed -> p2pManager.node.declineCall(peerId)
                                        ActionPerformed -> p2pManager.node.answerCall(peerId)
                                    }
                                }
                                    .also { currentSnackBars[peerId] = it }
                            }
                        }

                        else -> currentSnackBars[peerId]?.cancel()
                    }
                }
            }
        }
    }
}
