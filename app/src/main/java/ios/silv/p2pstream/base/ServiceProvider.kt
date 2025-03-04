package ios.silv.p2pstream.base

import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import ios.silv.p2pstream.feature.home.HomeViewModel

class ServiceProvider : DefaultServiceProvider() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        super.bindServices(serviceBinder)

        val scope = serviceBinder.scopeTag

        with(serviceBinder) {
            when (scope) {
                HomeViewModel::class.java.name -> {
                    add(HomeViewModel(lookup(), backstack))
                }
                else -> {
                }
            }
        }
    }
}