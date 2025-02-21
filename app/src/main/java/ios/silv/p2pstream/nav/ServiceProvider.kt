package ios.silv.p2pstream.nav

import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider
import com.zhuinden.simplestackextensions.servicesktx.add
import ios.silv.p2pstream.feature.home.MainViewModel

class ServiceProvider : DefaultServiceProvider() {

    override fun bindServices(serviceBinder: ServiceBinder) {
        super.bindServices(serviceBinder)

        val scope = serviceBinder.scopeTag

        with(serviceBinder) {
            when (scope) {
                MainViewModel::class.java.name -> {
                    add(MainViewModel(backstack))
                }
                else -> {
                }
            }
        }
    }
}