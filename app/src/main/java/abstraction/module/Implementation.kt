package abstraction.module

import android.content.Context
import android.util.*
import android.view.GestureDetector.*
import android.view.*
import androidx.fragment.app.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.Predicate
import java.lang.ref.WeakReference

object UI : (Context, ScreenEventInterceptor?) -> Pair<out Fragment, Int?> {
    override fun invoke(context: Context, interceptor: ScreenEventInterceptor?): Pair<out Fragment, Int?> =
        Pair(OverlayFragment(WeakReference(context), interceptor), null)
}

private open class OverlayFragment(
    private val context: WeakReference<out Context>,
    private val interceptor: ScreenEventInterceptor?
) : Fragment(),
    OnContextClickListener {
    override fun onContextClick(event: MotionEvent): Boolean {
        intercept(
            OnContextClickListener::onContextClick, event) {
                // post-process event
            }
        return true
    }

    private fun <R> intercept(member: KFunction<R>, vararg args: Any, postback: Runnable? = null): Boolean {
        interceptor?.invoke(this, member, args, postback).let {
            fun postback(): Boolean {
                postback?.run() ?: return false
                return true
            }
            return if (it === null)
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
}

private typealias ScreenEventInterceptor = (Any, KFunction<*>, Array<*>, Runnable?) -> Pair<Predicate?, Boolean?>?