package ios.silv.p2pstream

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.zhuinden.simplestack.BackHandlingModel
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.History
import com.zhuinden.simplestack.SimpleStateChanger
import com.zhuinden.simplestack.StateChange
import com.zhuinden.simplestack.navigator.Navigator
import com.zhuinden.simplestackextensions.lifecyclektx.observeAheadOfTimeWillHandleBackChanged
import com.zhuinden.simplestackextensions.navigatorktx.androidContentFrame
import ios.silv.p2pstream.feature.home.HomeKey
import ios.silv.p2pstream.base.FragmentStateChanger
import ios.silv.p2pstream.base.ServiceProvider

class MainActivity : AppCompatActivity(), SimpleStateChanger.NavigationHandler {

    private lateinit var fragmentStateChanger: FragmentStateChanger
    private lateinit var backstack: Backstack

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            backstack.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(backPressedCallback)
        fragmentStateChanger = FragmentStateChanger(supportFragmentManager, R.id.container)

        backstack = Navigator.configure()
            .setBackHandlingModel(BackHandlingModel.AHEAD_OF_TIME)
            .setStateChanger(SimpleStateChanger(this))
            .setGlobalServices(requireNotNull(application as? App).globalServices)
            .setScopedServices(ServiceProvider())
            .install(
                this, androidContentFrame, History.of(
                    HomeKey
                )
            )

        backPressedCallback.isEnabled = backstack.willHandleAheadOfTimeBack()
        backstack.observeAheadOfTimeWillHandleBackChanged(this, backPressedCallback::isEnabled::set)
    }

    override fun onNavigationEvent(stateChange: StateChange) {
        fragmentStateChanger.handleStateChange(stateChange)
    }
}