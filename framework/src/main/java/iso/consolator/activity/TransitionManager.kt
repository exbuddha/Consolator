package iso.consolator.activity

import iso.consolator.*

interface TransitionManager : Resolver {
    fun commit(source: Short = -1, destination: Short) = Unit
}