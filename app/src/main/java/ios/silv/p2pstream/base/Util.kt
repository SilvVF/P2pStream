package ios.silv.p2pstream.base

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@JvmName("mutate_List")
fun <T> MutableStateFlow<List<T>>.mutate(update: MutableList<T>.() -> Unit) {
    return update { value ->
        value.toMutableList().apply(update)
    }
}

@JvmName("mutate_Map")
fun <K, V> MutableStateFlow<Map<K, V>>.mutate(update: MutableMap<K, V>.() -> Unit) {
    return update { value ->
        value.toMutableMap().apply(update)
    }
}

class MutableStateFlowMap<K: Any, V: Any>(
    value: Map<K, V>
) : MutableStateFlow<Map<K, V>> by MutableStateFlow(value) {

    operator fun get(key: K): V? = this.value[key]

    operator fun set(key: K, value: V) {
        mutate {
            this[key] = value
        }
    }
}