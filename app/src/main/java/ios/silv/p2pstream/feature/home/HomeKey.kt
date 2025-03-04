package ios.silv.p2pstream.feature.home

import android.os.Parcelable
import androidx.fragment.app.Fragment
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.fragments.DefaultFragmentKey
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.get
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.base.FragmentKey
import ios.silv.p2pstream.net.P2pManager
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data object HomeKey: FragmentKey() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        with(serviceBinder) {
            add(HomeViewModel(lookup<P2pManager>(), backstack))
        }
    }

    override fun instantiateFragment(): Fragment = error("Not implemented")
}