package ios.silv.p2pstream

import android.app.Application
import com.zhuinden.simplestack.GlobalServices

class App: Application() {

    lateinit var globalServices: GlobalServices
        private set

    override fun onCreate() {
        super.onCreate()

        globalServices = GlobalServices.builder()
            .build()
    }
}