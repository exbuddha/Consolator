package abstraction.module

import androidx.fragment.app.*
import kotlin.reflect.*

fun Fragment(interceptor: (KFunction<*>, Array<*>) -> KFunction<*>) = OverlayFragment()

open class OverlayFragment : Fragment()