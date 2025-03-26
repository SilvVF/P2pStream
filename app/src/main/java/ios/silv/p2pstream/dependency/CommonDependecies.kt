package ios.silv.p2pstream.dependency

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import ios.silv.p2pstream.App
import ios.silv.p2pstream.base.AppNavigator
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.Dispatchers

/**
 * Global var for making the [CommonDependencies] accessible.
 */
@DependencyAccessor
public lateinit var commonDeps: CommonDependencies

@OptIn(DependencyAccessor::class)
public val LifecycleOwner.commonDepsLifecycle: CommonDependencies
    get() = commonDeps

/**
 * Access to various dependencies for common-app module.
 */
@OptIn(DependencyAccessor::class)
public abstract class CommonDependencies {

    abstract val application: Application

    val appNavigator = AppNavigator()

    val p2pManager by lazy {
        P2pManager(application, Dispatchers.IO).also { manager ->
            manager.start()
        }
    }
}