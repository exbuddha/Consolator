package net.consolator

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelStoreOwner
import kotlin.reflect.KFunction
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

class MainFragment : BaseFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState === null) {
            // show animation or progress bar
            parentFragmentManager.commit {
                show(this@MainFragment) }
            log(info, UI_TAG, "Main fragment view is created.") }
    }

    override var overlay = fun(viewModelStoreOwner: ViewModelStoreOwner, savedInstanceState: Bundle?, action: Short) =
        when (action) {
            COMMIT_NAV_MAIN_UI ->
                UI(requireActivity(), viewModelStoreOwner, savedInstanceState, action, ::screenEventInterceptor).apply {
                    // ...
                }
            ABORT_NAV_MAIN_UI ->
                null to null /* continue animation or alternatives */
            else ->
                throw BaseImplementationRestriction }

    private inline fun <reified R> screenEventInterceptor(listener: Any, callback: KFunction<R>, vararg args: Any?, noinline postback: PostbackFunction?): Interception = null
}

typealias Interception = Pair<AnyFunction?, Boolean?>?
typealias PostbackFunction = (Interception) -> Any?