package ios.silv.p2pstream.base

import androidx.annotation.CallSuper
import com.zhuinden.simplestack.Bundleable
import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.statebundle.StateBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

abstract class BaseViewModel: Bundleable, ScopedServices.Registered {

    protected val scope = CoroutineScope(Dispatchers.Main)

    override fun toBundle(): StateBundle = StateBundle()
    override fun fromBundle(bundle: StateBundle?) = Unit

    override fun onServiceRegistered() = Unit

    @CallSuper
    override fun onServiceUnregistered() {
        scope.cancel()
    }
}