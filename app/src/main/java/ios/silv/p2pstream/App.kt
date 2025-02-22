package ios.silv.p2pstream

import android.app.Application
import com.zhuinden.simplestack.GlobalServices
import com.zhuinden.simplestackextensions.servicesktx.add
import ios.silv.p2pstream.log.AndroidLogcatLogger
import ios.silv.p2pstream.log.LogPriority
import ios.silv.p2pstream.net.P2pManager
import kotlinx.coroutines.Dispatchers

class App: Application() {

    lateinit var globalServices: GlobalServices
        private set

    override fun onCreate() {
        super.onCreate()

        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = LogPriority.VERBOSE)

        val p2pManager = P2pManager(this@App, Dispatchers.IO)

        globalServices = GlobalServices.builder()
            .add(p2pManager)
            .build()
    }
}