@file:OptIn(ExperimentalMaterial3Api::class)

package ios.silv.p2pstream.feature.call


import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackcomposeintegration.core.LocalBackstack
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import io.libp2p.core.PeerId
import ios.silv.p2pstream.base.ComposeKey
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.annotation.concurrent.Immutable

@Immutable
@Parcelize
data class CallKey(val peerId: String): ComposeKey() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(CallViewModel(lookup<P2pManager>(), backstack))
        }
    }
    
    @Composable
    override fun ScreenComposable(modifier: Modifier) {
        val backstack = LocalBackstack.current
        val viewModel = remember { backstack.lookup<CallViewModel>() }

        DisposableEffect(Unit) {
            val job = viewModel.start(peerId)
            onDispose { job.cancel() }
        }

        ComposeContent(modifier)
    }
}

private val requiredPermission = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)


@Composable
private fun ComposeContent(modifier: Modifier) {
    val backstack = LocalBackstack.current
    val viewModel = remember { backstack.lookup<CallViewModel>() }
    val lifecycle = LocalLifecycleOwner.current

    val context = LocalContext.current
    val permissionState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(requiredPermission.zip(
                Array(requiredPermission.size) {
                    context.checkSelfPermission(requiredPermission[it]) == PackageManager.PERMISSION_GRANTED
                }
            ))
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (p, res) ->
            permissionState[p] = res
        }
    }

    val allGranted by remember(permissionState) {
        derivedStateOf { permissionState.all { it.value } }
    }

    if (allGranted) {
        val cameraStateHolder =
            remember { CameraStateHolder(context, lifecycle, viewModel.frameCh) }

        LaunchedEffect(Unit) {
            cameraStateHolder.lifecycleOwner.lifecycleScope.launch {
                cameraStateHolder.lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (cameraStateHolder.deferredCapabilities != null) {
                        cameraStateHolder.deferredCapabilities!!.await()
                        cameraStateHolder.deferredCapabilities = null
                    }
                }
            }

        }

        val sampledFrame by viewModel.sampledFrame.collectAsStateWithLifecycle()
        Scaffold(
            modifier,
            topBar = {
                TopAppBar(
                    title = {
                        val text by viewModel.currentState.collectAsStateWithLifecycle()
                        Text(text.toString())
                    }
                )
            }
        ) { paddingValues ->
            Box(Modifier.fillMaxSize()) {
                CameraComposeView(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    cameraState = cameraStateHolder
                )
                sampledFrame?.let {
                    Image(
                        remember { it.asImageBitmap() },
                        null,
                        Modifier.align(Alignment.TopStart)
                    )
                }
            }
        }
    } else {
        Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    launcher.launch(requiredPermission)
                }
            ) {
                Text("Grant Permissions")
            }
            permissionState.forEach { (p, v) ->
                Text("Perm: $p, state: $v")
            }
        }
    }
}

@Composable
private fun CameraComposeView(
    modifier: Modifier = Modifier,
    cameraState: CameraStateHolder
) {
    val lifecycle = LocalLifecycleOwner.current
    val state by cameraState.state.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                PreviewView(context).also { view ->
                    lifecycle.lifecycleScope.launch {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                            cameraState.bindCaptureUseCase(view)
                        }
                    }
                }
            },
        )
        CameraControls(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .height(200.dp)
        )
    }
}

@Composable
private fun CameraControls(
    modifier: Modifier,
    state: CameraStateHolder.State
) {

    val selectorStrings by remember {
        derivedStateOf {
            state.qualities.map { it.getNameString() }
        }
    }

    Row(
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier
                .height(200.dp)
                .weight(1f)
        ) {
            itemsIndexed(selectorStrings) { i, quality ->
                FilterChip(
                    selected = (i == state.qualityIdx),
                    enabled = state.uiEnabled,
                    onClick = {
                        state.handleEvent(CameraUiEvent.ChangeQuality(i))
                    },
                    label = { Text(quality) })
            }
        }
        Button(enabled = state.uiEnabled, onClick = {
            val idx = if (state.cameraIdx == 0) state.capabilities.lastIndex else 0
            state.handleEvent(CameraUiEvent.ChangeCamera(idx))
        }) {
            Text("flip")
        }
    }
}