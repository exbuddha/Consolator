package consolator.module

import android.os.Bundle
import android.util.Pair
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import kotlin.reflect.KFunction
import backgammon.module.BaseFragment
import backgammon.module.BaseImplementationRestriction
import backgammon.module.Predicate
import backgammon.module.ACTION_KEY
import backgammon.module.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import backgammon.module.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

class MainFragment : BaseFragment() {
    override var overlay = fun(_: View, bundle: Bundle?): Pair<out Fragment?, Int?> =
        when (bundle?.getShort(ACTION_KEY, -1)) {
            COMMIT_NAV_MAIN_UI ->
                abstraction.module.UI(requireActivity(), ::screenEventInterceptor).apply {
                    // ...
                }
            ABORT_NAV_MAIN_UI ->
                Pair(null, null) // continue animation or alternatives
            else ->
                throw BaseImplementationRestriction
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // show animation or progress bar
        parentFragmentManager.commit {
            show(this@MainFragment)
        }
    }

    private inline fun <reified R> screenEventInterceptor(
        listener: Any,
        callback: KFunction<R>,
        vararg param: Any?,
        r: Runnable?
    ): Pair<Predicate?, Boolean?>? = null
}