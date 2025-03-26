package ios.silv.p2pstream.dependency

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * helper to get a dep from compose without having to annotate
 * still findable with search for [rememberDependency]
 */
@Composable
@OptIn(DependencyAccessor::class)
fun <T> rememberDependency(get: CommonDependencies.() -> T) = remember(commonDeps) { commonDeps.get() }