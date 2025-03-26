package ios.silv.p2pstream.base

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription

/***
 *  Default provided for convince in previews
 */
val LocalNavigator = staticCompositionLocalOf { AppNavigator() }

typealias NavCmd = NavController.() -> Unit

class AppNavigator {

    val navCmds = MutableSharedFlow<NavCmd>(extraBufferCapacity = Int.MAX_VALUE)

    // We use a StateFlow here to allow ViewModels to start observing navigation results before the initial composition,
    // and still get the navigation result later
    val navControllerFlow = MutableStateFlow<NavController?>(null)

    fun nav(block: NavCmd) {
        navCmds.tryEmit(block)
    }

    suspend fun handleNavigationCommands(navController: NavController) {
        navCmds
            .onSubscription { this@AppNavigator.navControllerFlow.value = navController }
            .onCompletion { this@AppNavigator.navControllerFlow.value = null }
            .collect { cmd ->
                logcat { "sending nav command $cmd" }
                navController.cmd()
            }
    }
}