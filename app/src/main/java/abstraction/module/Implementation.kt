package abstraction.module

import android.util.*
import androidx.fragment.app.*
import kotlin.reflect.*
import kotlinx.coroutines.*

fun Fragment(interceptor: (Any, KFunction<*>, Array<*>, Runnable?) -> Pair<KFunction<*>?, Boolean?>?) =
    OverlayFragment()

open class OverlayFragment : Fragment()