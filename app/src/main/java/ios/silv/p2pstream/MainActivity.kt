package ios.silv.p2pstream

import android.annotation.SuppressLint
import android.os.Bundle
import android.telecom.Call
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.AnimBuilder
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.zhuinden.simplestack.AsyncStateChanger
import com.zhuinden.simplestack.BackHandlingModel
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.Backstack.CompletionListener
import com.zhuinden.simplestack.History
import com.zhuinden.simplestack.SimpleStateChanger
import com.zhuinden.simplestack.SimpleStateChanger.NavigationHandler
import com.zhuinden.simplestack.StateChange
import com.zhuinden.simplestack.StateChanger
import com.zhuinden.simplestack.StateChanger.Callback
import com.zhuinden.simplestack.navigator.Navigator
import com.zhuinden.simplestackextensions.fragments.DefaultFragmentKey
import com.zhuinden.simplestackextensions.fragments.R.*
import com.zhuinden.simplestackextensions.lifecyclektx.observeAheadOfTimeWillHandleBackChanged
import com.zhuinden.simplestackextensions.navigatorktx.androidContentFrame
import ios.silv.p2pstream.base.FragmentStateChanger
import ios.silv.p2pstream.base.ServiceProvider
import ios.silv.p2pstream.base.backstackComposable
import ios.silv.p2pstream.databinding.ActivityMainBinding
import ios.silv.p2pstream.feature.call.CallFragment
import ios.silv.p2pstream.feature.call.CallKey
import ios.silv.p2pstream.feature.home.HomeFragment
import ios.silv.p2pstream.feature.home.HomeKey
import ios.silv.p2pstream.log.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch

class ComposeStateChanger(
    lifecycle: Lifecycle
) : StateChanger, NavigationHandler {

    private lateinit var navController: NavHostController
    private val initialized = Channel<Nothing>(RENDEZVOUS)

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val navCh = Channel<Pair<StateChange, Callback>>(UNLIMITED)

    init {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {

                initialized.receiveCatching()

                for ((stateChange, completionCb) in navCh) {
                    onNavigationEvent(stateChange)
                    completionCb.stateChangeComplete()
                }
            }
        }
    }

    override fun handleStateChange(stateChange: StateChange, completionCallback: Callback) {
        logcat { "${stateChange.getPreviousKeys<DefaultFragmentKey>()} -> ${stateChange.getNewKeys<DefaultFragmentKey>()}" }
        logcat { "$stateChange, $completionCallback, ${stateChange.isTopNewKeyEqualToPrevious}" }
        if (stateChange.isTopNewKeyEqualToPrevious) {
            completionCallback.stateChangeComplete();
            return;
        }
        navCh.trySendBlocking(stateChange to completionCallback)
    }

    fun bind(navController: NavHostController) {
        this.navController = navController
        initialized.close()
    }

    private fun AnimBuilder.applyDirections(stateChange: StateChange) {
        when (stateChange.direction) {
            StateChange.FORWARD -> {
                enter =
                    anim.slide_in_from_right
                exit = anim.slide_out_to_left
                popEnter =
                    anim.slide_in_from_right
                popExit =
                    anim.slide_out_to_left
            }

            StateChange.BACKWARD -> {
                enter =
                    anim.slide_in_from_left
                exit =
                    anim.slide_out_to_right
                popEnter =
                    anim.slide_in_from_left
                popExit =
                    anim.slide_out_to_right
            }

            else -> { // REPLACE
                enter = FragmentTransaction.TRANSIT_FRAGMENT_FADE
                exit = FragmentTransaction.TRANSIT_FRAGMENT_FADE
                popEnter = FragmentTransaction.TRANSIT_FRAGMENT_FADE
                popExit = FragmentTransaction.TRANSIT_FRAGMENT_FADE
            }
        }
    }

    override fun onNavigationEvent(stateChange: StateChange) {
        val topNewKey = stateChange.topNewKey<DefaultFragmentKey>()

        val newKeys = stateChange.getNewKeys<DefaultFragmentKey>()
        val prevKeys = stateChange.getPreviousKeys<DefaultFragmentKey>()

        val toRemove = prevKeys.subtract(newKeys)
        logcat { "removing $toRemove" }
        for (key in toRemove) {
            navController.popBackStack(key, inclusive = true, saveState = true)
        }

        for (key in newKeys) {
            if (key == topNewKey) {
                continue
            }
            navController.navigate(key) {
                restoreState = true
            }
        }
        navController.navigate(topNewKey) {
            anim {
                applyDirections(stateChange)
            }
        }
    }
}

class MainActivity : AppCompatActivity(), NavigationHandler {

    private lateinit var binding: ActivityMainBinding
    private lateinit var composeStateChanger: ComposeStateChanger
    private lateinit var backstack: Backstack

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            logcat { "Activity on back pressed" }
            backstack.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        onBackPressedDispatcher.addCallback(backPressedCallback)
        composeStateChanger = ComposeStateChanger(lifecycle)

        backstack = Navigator.configure()
            .setBackHandlingModel(BackHandlingModel.AHEAD_OF_TIME)
            .setStateChanger(composeStateChanger)
            .setGlobalServices(requireNotNull(application as? App).globalServices)
            .setScopedServices(ServiceProvider())
            .install(
                this, androidContentFrame, History.of(
                    HomeKey
                )
            )

        backPressedCallback.isEnabled = backstack.willHandleAheadOfTimeBack()
        backstack.observeAheadOfTimeWillHandleBackChanged(this, backPressedCallback::isEnabled::set)

        ComposeView(this).apply {
            // Dispose the Composition when the view's LifecycleOwner
            // is destroyed
            binding.container.addView(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val navController = rememberNavController()

                MaterialTheme {
                    NavHost(
                        navController = navController,
                        startDestination = backstack.getInitialKeys<DefaultFragmentKey>().first(),
                    ) {
                        backstackComposable<HomeKey>(backstack) {
                            HomeFragment.Content(it)
                        }
                        backstackComposable<CallKey>(backstack) {
                            CallFragment.Content(it)
                        }
                    }
                }


                SideEffect {
                    composeStateChanger.bind(navController)
                }
            }
        }
    }

    override fun onNavigationEvent(stateChange: StateChange) {
        composeStateChanger.handleStateChange(stateChange) {
            logcat { "Completed navigation" }
        }
    }
}