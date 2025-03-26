@file:OptIn(ExperimentalMaterial3Api::class)

package ios.silv.p2pstream.feature.call


import android.Manifest
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import ios.silv.p2pstream.base.createViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class CallScreen(val peerId: String)

fun NavGraphBuilder.callScreen() {
    composable<CallScreen> { backStackEntry ->

        val viewModel = backStackEntry.createViewModel { savedStateHandle -> CallViewModel(savedStateHandle) }

        DisposableEffect(Unit) {
            val job = viewModel.start()
            onDispose { job.cancel() }
        }

        ComposeContent(viewModel)
    }
}


private val requiredPermission = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)


@Composable
private fun ComposeContent(
    viewModel: CallViewModel,
    modifier: Modifier = Modifier
) {

    val lifecycle = LocalLifecycleOwner.current

    val context = LocalContext.current
    val permissionState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(
                requiredPermission.zip(
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
            remember(viewModel) { CameraStateHolder(context, lifecycle, FRAME_CH) }
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
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                CameraComposeView(
                    modifier = Modifier
                        .fillMaxSize(),
                    cameraState = cameraStateHolder
                )
                Box(
                    Modifier.sizeIn(maxHeight = 200.dp, maxWidth = 200.dp)
                        .align(Alignment.TopStart)
                        .clip(MaterialTheme.shapes.medium)
                        .clipToBounds()
                        .border(2.dp, Color.Red)
                ) {
                    AndroidView(
                        modifier = Modifier,
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        val surface = holder.surface
                                        viewModel.bind(surface)
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) {
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        viewModel.unbind()
                                    }
                                })
                            }
                        }
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
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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

    val selectorStrings by remember(state.capabilities, state.cameraIdx) {
        derivedStateOf {
            state.capabilities.getOrNull(state.cameraIdx)
                ?.qualities
                ?.map { it.getNameString().ifEmpty { "Unknown format" } }
                ?: emptyList()
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