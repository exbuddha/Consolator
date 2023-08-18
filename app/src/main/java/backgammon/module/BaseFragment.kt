package backgammon.module

import android.content.Context
import android.os.*
import android.util.*
import android.view.*
import androidx.fragment.app.*
import androidx.fragment.app.FragmentTransaction.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.application.*
import backgammon.module.BaseApplication.Companion.ACTION_NAV_MAIN_UI
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP

abstract class BaseFragment : Fragment() {
    private val viewModel
        get() = activity?.asType<BaseActivity>()?.viewModel

    override fun onAttach(context: Context) {
        super.onAttach(context)
        launch(Dispatchers.IO) @JobTreeRoot {
            trySafelyCanceling {
                with(context) {
                    if (db === null)
                        db = buildDatabase()
                    schedule(Context::signalDbCreated)
                    if (session === null)
                        session = with(db!!.runtimeDao()) {
                            tryCancelingForResult {
                                getSession(
                                    newSession(instance!!.startTime))
                            }
                        }
                    schedule(Context::signalSessionCreated)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launch @MainViewGroup {
            Scheduler.EventBus.collect {
                when (it?.transit) {
                    ACTION_NAV_MAIN_UI ->
                        viewModel?.apply {
                            schedule {
                                parentFragmentManager.commit {
                                    setTransition(TRANSIT_FRAGMENT_OPEN)
                                    replace(
                                        this@BaseFragment.id,
                                        transitFragment(view, savedInstanceState?.apply {
                                            putShort(ACTION_KEY, ACTION_NAV_MAIN_UI)
                                        }))
                                }
                            }
                        }
                    ACTION_MIGRATE_APP ->
                        Scheduler.defer(::onViewCreated, Migration::class)
                }
            }
        }
        Log.i(UI_TAG, "Main fragment view is created.")
    }

    override fun onResume() {
        if (State[1] === State.Finished) {
            with(Scheduler) {
                ignore()
                clock?.apply {
                    Process.setThreadPriority(threadId, Thread.NORM_PRIORITY)
                }
                sequencer = null
            }
        }
        super.onResume()
    }

    private var transitFragment = fun(_: View, bundle: Bundle?): Fragment {
        if (bundle?.getShort(ACTION_KEY, -1) == ACTION_NAV_MAIN_UI)
            abstraction.module.Fragment(::screenEventInterceptor).apply {
                // ...
            }
        throw BaseImplementationRestriction
    }

    inline fun <reified R> screenEventInterceptor(callback: KFunction<R>, vararg param: Any?): KFunction<R> = TODO()

    protected annotation class MainViewGroup

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}