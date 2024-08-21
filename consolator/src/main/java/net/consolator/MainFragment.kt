@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package net.consolator

import android.os.*
import android.view.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import iso.consolator.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN
import kotlinx.coroutines.Dispatchers.Default
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

@Tag(MAIN_FRAGMENT)
internal open class MainFragment : BaseFragment(), Provider {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (this is OverlayFragment)
            return super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState === null)
            transit = fun(action: Short) { when (action) {
                COMMIT_NAV_MAIN_UI ->
                    schedule @Implicit {
                    parentFragmentManager.commit {
                        setTransition(TRANSIT_FRAGMENT_OPEN)
                        replace(
                            this@MainFragment.id,
                            OverlayFragment(this@MainFragment, ::viewEventInterceptor)
                            .apply {
                            /* renew main view */
                            }) } }
                ABORT_NAV_MAIN_UI -> {
                    /* continue animation or alternatives */ }
                else ->
                    throw BaseImplementationRestriction()
        } }
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState === null) {
            // show animation or progress bar
            parentFragmentManager.commit {
                show(this@MainFragment) }
            currentThread.log(info, UI_TAG, "Main fragment view is created.") }
    }

    protected inline fun <reified R> viewEventInterceptor(listener: Any, callback: KFunction<R>, vararg args: Any?, noinline postback: PostbackFunction?): Interception =
        (null to false)
        .also { interception ->
        lifecycleScope.launch(Default) { postback?.invoke(interception) } }

    override fun invoke(type: AnyKClass) = activity.asObjectProvider()!!(type)

    override fun <R> invoke(vararg tag: TagType): KCallable<R> = activity.asFunctionProvider()!!(*tag)
}