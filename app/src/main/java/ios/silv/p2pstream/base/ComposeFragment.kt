package ios.silv.p2pstream.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModel
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.simplestackextensions.fragments.KeyedFragment
import com.zhuinden.simplestackextensions.fragmentsktx.backstack
import com.zhuinden.simplestackextensions.servicesktx.lookup

abstract class ComposeFragment : KeyedFragment() {

    @Composable
    abstract fun FragmentComposable(backstack: Backstack)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val backstack = backstack

        return ComposeView(requireContext()).apply {
            // Dispose the Composition when the view's LifecycleOwner
            // is destroyed
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    FragmentComposable(backstack)
                }
            }
        }
    }

    @Composable
    inline fun <reified T: ScopedServices.Registered> backStackViewModel() = remember { backstack.lookup<T>() }
}