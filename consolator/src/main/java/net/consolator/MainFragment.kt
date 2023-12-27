package net.consolator

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import kotlin.reflect.KFunction
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

class MainFragment : BaseFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // show animation or progress bar
        super.onViewCreated(view, savedInstanceState)
        parentFragmentManager.commit {
            show(this@MainFragment)
        }
        if (info.isOn)
            info(UI_TAG, "Main fragment view is created.")
    }

    override var overlay = fun(_: View, bundle: Bundle?): Pair<Fragment?, Int?> =
        when (bundle?.getShort(ACTION_KEY, -1)) {
            COMMIT_NAV_MAIN_UI ->
                UI(requireActivity(), ::screenEventInterceptor).apply {
                    // ...
                }
            ABORT_NAV_MAIN_UI ->
                Pair(null, null) // continue animation or alternatives
            else ->
                throw BaseImplementationRestriction
        }
    private inline fun <reified R> screenEventInterceptor(
        listener: Any,
        callback: KFunction<R>,
        vararg param: Any?,
        r: Runnable?
    ): Pair<Predicate?, Boolean?>? = null
}