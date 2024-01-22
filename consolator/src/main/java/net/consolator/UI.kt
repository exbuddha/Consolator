package net.consolator

import android.content.*
import android.view.*
import android.view.GestureDetector.*
import androidx.fragment.app.*
import kotlin.reflect.*

object UI : (Context, ScreenEventInterceptor?) -> Pair<Fragment, Int?> {
    override fun invoke(context: Context, interceptor: ScreenEventInterceptor?): Pair<Fragment, Int?> =
        Pair(OverlayFragment(context.weakRef()!!, interceptor), null)
}

private open class OverlayFragment(
    private var context: WeakContext,
    private val interceptor: ScreenEventInterceptor?)
    : Fragment(),
    OnContextClickListener {
    override fun onContextClick(event: MotionEvent) =
        intercept(
            OnContextClickListener::onContextClick, event) {
                // process event internal to overlay view.
                // translation for finding the event receiver view may be required sometimes.
                // optionally, other/all event listener functionality can be given to this class.
            }

    private fun <R> intercept(member: KFunction<R>, vararg args: Any, postback: AnyToAnyFunction? = null) =
        interceptor?.invoke(this, member, args, postback).let { result ->
            fun postback(): Boolean {
                postback?.invoke(result) ?: return false
                return true }
            if (result === null)
                postback()
            else {
                val (callback, filter) = result
                if (filter == true)
                    callback?.invoke() ?: postback()
                else postback() } }
}

private typealias ScreenEventInterceptor = (Any, AnyKFunction, AnyArray, AnyToAnyFunction?) -> Interception