// WITH_RUNTIME
// WITH_COROUTINES
import helpers.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

inline fun schedule(crossinline runner: suspend () -> Unit)  {
    builder {
        runner()
    }
}

fun box(): String {
    var res = "FAIL"
    schedule {
        res = "OK"
    }
    return res
}
