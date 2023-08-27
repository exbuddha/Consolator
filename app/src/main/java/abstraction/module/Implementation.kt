package abstraction.module

import android.content.Context
import android.util.*
import android.view.*
import android.view.GestureDetector.*
import androidx.fragment.app.*
import java.lang.ref.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.Predicate

object UI : (Context, ScreenEventInterceptor?) -> Pair<out Fragment, Int?> {
    override fun invoke(context: Context, interceptor: ScreenEventInterceptor?): Pair<out Fragment, Int?> =
        Pair(OverlayFragment(WeakReference(context), interceptor), null)
}

private open class OverlayFragment(
    private val context: WeakReference<out Context>,
    private val interceptor: ScreenEventInterceptor?
) : Fragment(),
    OnContextClickListener {
    override fun onContextClick(event: MotionEvent) =
        intercept(
            OnContextClickListener::onContextClick, event) {
                // process event internal to overlay view.
                // translation for finding the event receiver view may be required sometimes.
                // optionally, other/all event listener functionality can be given to this class.
            }

    private fun <R> intercept(member: KFunction<R>, vararg args: Any, postback: Runnable? = null) =
        interceptor?.invoke(this, member, args, postback).let {
            fun postback(): Boolean {
                postback?.run() ?: return false
                return true
            }
            if (it === null)
                postback()
            else
                with(it) {
                    if (second != true)
                        postback()
                    else
                        first?.invoke() ?: postback()
                }
        }
}

private typealias ScreenEventInterceptor = (Any, KFunction<*>, Array<*>, Runnable?) -> Pair<Predicate?, Boolean?>?