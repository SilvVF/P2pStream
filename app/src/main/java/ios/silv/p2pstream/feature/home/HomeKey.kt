package ios.silv.p2pstream.feature.home

import androidx.fragment.app.Fragment
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import ios.silv.p2pstream.nav.FragmentKey
import kotlinx.parcelize.Parcelize

@Parcelize
data object HomeKey: FragmentKey() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(MainViewModel(backstack))
        }
    }

    override fun instantiateFragment(): Fragment = HomeFragment()
}