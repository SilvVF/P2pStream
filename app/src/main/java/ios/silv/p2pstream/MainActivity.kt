package ios.silv.p2pstream

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult.ActionPerformed
import androidx.compose.material3.SnackbarResult.Dismissed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.libp2p.core.PeerId
import ios.silv.p2pstream.dependency.rememberDependency
import ios.silv.p2pstream.feature.call.callScreen
import ios.silv.p2pstream.feature.home.HomeScreen
import ios.silv.p2pstream.feature.home.homeScreen
import ios.silv.p2pstream.log.logcat
import ios.silv.p2pstream.net.CallState.DIALED
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {

            val snackbarState = remember { SnackbarHostState() }
            val navController = rememberNavController()
            val navigator = rememberDependency { appNavigator }

            LaunchedEffect(navController) {
                navigator.handleNavigationCommands(navController)
            }

            MaterialTheme {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarState) }
                ) { paddingValues ->
                    Box(Modifier.padding(paddingValues)) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeScreen
                        ) {
                            homeScreen()
                            callScreen()
                        }
                        DisplayCallSnackBars(snackbarState)
                    }
                }
            }
        }
    }
}


@Composable
private fun DisplayCallSnackBars(
    snackbarState: SnackbarHostState
) {
    val lifecycle = LocalLifecycleOwner.current
    val p2pManager = rememberDependency { p2pManager }
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
