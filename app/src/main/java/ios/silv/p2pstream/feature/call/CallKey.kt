package ios.silv.p2pstream.feature.call

import androidx.fragment.app.Fragment
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.base.FragmentKey
import ios.silv.p2pstream.net.P2pManager
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data object CallKey: FragmentKey() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(CallViewModel(lookup<P2pManager>(), backstack))
        }
    }

    override fun instantiateFragment(): Fragment = error("Not implemented")
}