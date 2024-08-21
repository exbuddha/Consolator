package iso.consolator

import kotlin.reflect.*

interface Interceptor {
    var interceptor: InterceptFunction?

    fun <R> onNullInterceptor(cls: AnyKClass): R = iso.consolator.onNullInterceptor(cls)
    fun <R> onNullInterception(cls: AnyKClass): R = iso.consolator.onNullInterception(cls)
    fun <R> onCallbackNotRequired(cls: AnyKClass): R = iso.consolator.onCallbackNotRequired(cls)
    fun <R> onNullCallback(cls: AnyKClass): R = iso.consolator.onNullCallback(cls)
    fun <R> onNullInteraction(cls: AnyKClass): R = iso.consolator.onNullInteraction(cls)
}

inline fun <reified R> Interceptor.intercept(member: KFunction<R>, vararg args: Any, noinline postback: (suspend (Interception) -> R)? = null): R =
    interceptor?.run {
        return invoke(this, member, args, postback).let { result ->
        if (result === null)
            onNullInterception(member.returnType())
        else {
            val (callback, isRequired) = result
            if (isRequired)
                if (callback !== null)
                    callback.invoke(null).asType()
                    ?: onNullInteraction(member.returnType())
                else onNullCallback(member.returnType())
            else onCallbackNotRequired(member.returnType()) } }
    } ?: onNullInterceptor(member.returnType())

private fun <R> onNullInterceptor(cls: AnyKClass): R = callbackNegativeType(cls)
private fun <R> onNullInterception(cls: AnyKClass): R = callbackNegativeType(cls)
private fun <R> onCallbackNotRequired(cls: AnyKClass): R = callbackPositiveType(cls)
private fun <R> onNullCallback(cls: AnyKClass): R = callbackPositiveType(cls)
private fun <R> onNullInteraction(cls: AnyKClass): R = callbackPositiveType(cls)

@Suppress("UNCHECKED_CAST")
fun <R> callbackPositiveType(cls: AnyKClass) = when (cls) {
    Boolean::class -> true
    else ->
        throw BaseImplementationRestriction() } as R

@Suppress("UNCHECKED_CAST")
fun <R> callbackNegativeType(cls: AnyKClass) = when (cls) {
    Boolean::class -> false
    else ->
        throw BaseImplementationRestriction() } as R

typealias InterceptFunction = (Any, AnyKFunction, AnyArray, PostbackFunction?) -> Interception
typealias PostbackFunction = suspend (Interception) -> Any?
typealias Interception = Pair<InterceptCallback, InterceptResult>?
private typealias InterceptCallback = AnyToAnyFunction?
private typealias InterceptResult = Boolean