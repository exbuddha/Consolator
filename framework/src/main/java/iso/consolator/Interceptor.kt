package iso.consolator

import kotlin.reflect.*
import kotlin.reflect.jvm.*

interface Interceptor {
    var interceptor: InterceptFunction?
}

inline fun <reified R> Interceptor.intercept(member: KFunction<R>, vararg args: Any, noinline postback: ((Interception) -> R)? = null): R =
    interceptor?.invoke(this, member, args, postback).let { result ->
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
    ?: onNullInterceptor(member.returnType())

inline fun <reified R> onNullInterceptor(cls: AnyKClass): R = postbackNegativeType()
inline fun <reified R> onNullInterception(cls: AnyKClass): R = onNullInterceptor(cls)
inline fun <reified R> onCallbackNotRequired(cls: AnyKClass): R = postbackPositiveType()
inline fun <reified R> onNullCallback(cls: AnyKClass): R = postbackNegativeType()
inline fun <reified R> onNullInteraction(cls: AnyKClass): R = postbackPositiveType()

inline fun <reified R> postbackPositiveType() = when (R::class) {
    Boolean::class -> true as R
    else ->
        throw BaseImplementationRestriction() }

inline fun <reified R> postbackNegativeType() = when (R::class) {
    Boolean::class -> false as R
    else ->
        throw BaseImplementationRestriction() }

fun AnyKFunction.returnType() = returnType.jvmErasure

typealias InterceptFunction = (Any, AnyKFunction, AnyArray, PostbackFunction?) -> Interception
typealias Interception = Pair<InterceptCallback, InterceptResult>?
typealias InterceptCallback = AnyToAnyFunction?
typealias InterceptResult = Boolean
typealias PostbackFunction = (Interception) -> Any?