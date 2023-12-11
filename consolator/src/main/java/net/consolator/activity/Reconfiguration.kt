package net.consolator.activity

import net.consolator.CoroutineStep
import net.consolator.Resolver
import net.consolator.Work
import net.consolator.asType

abstract class Reconfiguration : Resolver {
    override fun commit(vararg context: Any?) {
        context.lastOrNull().asType<Work>()?.invoke()
    }

    override fun commit(step: CoroutineStep): Boolean = TODO()
}