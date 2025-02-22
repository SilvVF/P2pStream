package ios.silv.p2pstream.feature.home

import androidx.fragment.app.Fragment
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.get
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.base.FragmentKey
import ios.silv.p2pstream.net.P2pManager
import kotlinx.parcelize.Parcelize

@Parcelize
data object HomeKey: FragmentKey() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(HomeViewModel(lookup<P2pManager>(), backstack))
        }
    }

    override fun instantiateFragment(): Fragment = HomeFragment()
}