package ios.silv.p2pstream.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.nav.ComposeFragment

class HomeFragment: ComposeFragment() {

    @Composable
    override fun FragmentComposable(backstack: Backstack) {

        val viewModel = backStackViewModel<HomeViewModel>()


    }
}