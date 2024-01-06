package net.consolator.application

import net.consolator.CoroutineStep
import net.consolator.Resolver

class MemoryManager : Resolver {
    override fun commit(vararg context: Any?) {}
    override fun commit(step: CoroutineStep): Boolean = TODO()
    private companion object
}