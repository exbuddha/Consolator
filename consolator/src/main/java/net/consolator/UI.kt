package net.consolator

import android.content.*
import android.os.*
import android.view.*
import android.view.GestureDetector.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*

var UI = fun(context: Context, _: ViewModelStoreOwner, _: Bundle?, _: Short, interceptor: InterceptFunction?): Transition =
    OverlayFragment(context.weakRef(), interceptor) to null

private open class OverlayFragment(
    private var context: WeakContext,
    private var interceptor: InterceptFunction?
) : Fragment(), OnContextClickListener {
    override fun onContextClick(event: MotionEvent) =
        intercept(
            OnContextClickListener::onContextClick, event) {
                /* process event internal to overlay view.
                // translation for finding the event receiver view may be required sometimes.
                // optionally, other/all event listener functionality can be given to this class. */
                true
            }

    private inline fun <reified R> intercept(member: KFunction<R>, vararg args: Any, noinline postback: ((Interception) -> R)? = null): R =
        interceptor?.invoke(this, member, args, postback).let { result ->
            if (result === null)
                onNullInterception(member.returnType())
            else {
                val (callback, isRequired) = result
                if (isRequired == true)
                    if (callback !== null)
                        callback.invoke().asType() ?:
                        onNullInteraction(member.returnType())
                    else onNullCallback(member.returnType())
                else onCallbackNotRequired(member.returnType())
            } } ?: onNullInterceptor(member.returnType())
}

private inline fun <reified R> onNullInterceptor(cls: AnyKClass): R = postbackNegativeType()
private inline fun <reified R> onNullInterception(cls: AnyKClass): R = onNullInterceptor(cls)
private inline fun <reified R> onCallbackNotRequired(cls: AnyKClass): R = postbackPositiveType()
private inline fun <reified R> onNullCallback(cls: AnyKClass): R = postbackNegativeType()
private inline fun <reified R> onNullInteraction(cls: AnyKClass): R = postbackPositiveType()

private inline fun <reified R> postbackPositiveType() = when (R::class) {
    Boolean::class -> true as R
    else ->
        throw BaseImplementationRestriction }

private inline fun <reified R> postbackNegativeType() = when (R::class) {
    Boolean::class -> false as R
    else ->
        throw BaseImplementationRestriction }

private fun AnyKFunction.returnType() = returnType.jvmErasure

private typealias InterceptFunction = (Any, AnyKFunction, AnyArray, PostbackFunction?) -> Interception