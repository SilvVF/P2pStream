package ios.silv.p2pstream

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.zhuinden.simplestack.History
import com.zhuinden.simplestackcomposeintegration.core.ComposeNavigator
import ios.silv.p2pstream.base.ServiceProvider
import ios.silv.p2pstream.feature.home.HomeKey

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                ComposeNavigator {
                    createBackstack(
                        initialKeys = History.of(HomeKey),
                        scopedServices = ServiceProvider(),
                        globalServices = (application as App).globalServices
                    )
                }
            }
        }
    }
}