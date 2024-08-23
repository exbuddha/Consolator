package iso.consolator.fragment

import iso.consolator.*

interface TransitionManager : Resolver {
    fun commit(source: Short = -1, destination: Short)
}

fun Any?.asTransitFunction() = asType<(TransitionManager) -> Unit>()