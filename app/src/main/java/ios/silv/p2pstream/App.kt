package ios.silv.p2pstream

import android.app.Application
import ios.silv.p2pstream.dependency.CommonDependencies
import ios.silv.p2pstream.dependency.DependencyAccessor
import ios.silv.p2pstream.dependency.commonDeps
import ios.silv.p2pstream.log.AndroidLogcatLogger
import ios.silv.p2pstream.log.LogPriority

class App: Application() {

    @OptIn(DependencyAccessor::class)
    override fun onCreate() {
        super.onCreate()

        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = LogPriority.VERBOSE)

        commonDeps = object : CommonDependencies() {
            override val application: Application = this@App
        }
    }
}