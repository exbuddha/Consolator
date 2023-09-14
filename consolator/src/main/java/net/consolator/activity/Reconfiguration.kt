package net.consolator.activity

import net.consolator.Resolver
import net.consolator.Work
import net.consolator.asType

abstract class Reconfiguration : Resolver {
    override fun commit(vararg context: Any?) {
        context.lastOrNull().asType<Work>()?.invoke()
    }
}