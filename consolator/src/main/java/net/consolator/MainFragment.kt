package net.consolator

import android.os.*
import android.view.*
import androidx.fragment.app.*
import iso.consolator.*
import iso.consolator.State.*
import kotlin.reflect.*
import androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

@Tag(MAIN_FRAGMENT)
internal open class MainFragment : BaseFragment(), ObjectProvider, FunctionProvider {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (State[3] is Resolved) return
        if (savedInstanceState === null)
            transit = fun(action: Short) { when (action) {
                COMMIT_NAV_MAIN_UI ->
                    schedule @Implicit {
                    parentFragmentManager.commit {
                        setTransition(TRANSIT_FRAGMENT_OPEN)
                        replace(
                            this@MainFragment.id,
                            OverlayFragment(this@MainFragment, ::screenEventInterceptor)
                            .apply {
                                /* renew main view */
                            })
                        State[3] = Resolved } }
                ABORT_NAV_MAIN_UI -> {
                    /* continue animation or alternatives */
                    State[3] = Ambiguous }
                else ->
                    throw BaseImplementationRestriction()
        } }
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState === null) {
            // show animation or progress bar
            parentFragmentManager.commit {
                show(this@MainFragment) }
            log(info, UI_TAG, "Main fragment view is created.") }
    }

    override fun invoke(type: AnyKClass) = activity.asObjectProvider()!!(type)

    override fun <R> invoke(vararg tag: TagType): KCallable<R> = activity.asFunctionProvider()!!(*tag)
}

internal inline fun <reified R> screenEventInterceptor(listener: Any, callback: KFunction<R>, vararg args: Any?, noinline postback: PostbackFunction?): Interception = null

internal typealias Interception = Pair<AnyFunction?, Boolean?>?
internal typealias PostbackFunction = (Interception) -> Any?