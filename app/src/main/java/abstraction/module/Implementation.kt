package abstraction.module

import android.util.*
import androidx.fragment.app.*
import kotlin.reflect.*
import kotlinx.coroutines.*

fun Fragment(interceptor: (Any, KFunction<*>, Array<*>, Runnable?) -> Pair<KFunction<*>?, Boolean?>?): Pair<Fragment, Int?> =
    Pair(OverlayFragment(), null)

open class OverlayFragment : Fragment()