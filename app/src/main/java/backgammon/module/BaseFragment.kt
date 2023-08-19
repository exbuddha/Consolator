package backgammon.module

import android.content.Context
import android.os.*
import android.util.*
import android.view.*
import androidx.core.util.component1
import androidx.core.util.component2
import androidx.fragment.app.*
import androidx.fragment.app.FragmentTransaction.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.Scheduler.Event.Listening
import backgammon.module.BaseApplication.Companion.ACTION_NAV_MAIN_UI
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP
import backgammon.module.application.*

abstract class BaseFragment : Fragment() {
    private val viewModel
        get() = activity?.asType<BaseActivity>()?.viewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentFragmentManager.commit {
            show(this@BaseFragment)
        }
        launch @MainViewGroup @Listening {
            Scheduler.EventBus.collect {
                when (it?.transit) {
                    ACTION_NAV_MAIN_UI -> {
                        viewModel?.apply {
                            schedule {
                                parentFragmentManager.commit {
                                    val (overlay, transition) =
                                        navigate(view, savedInstanceState?.apply {
                                            putShort(ACTION_KEY, ACTION_NAV_MAIN_UI)
                                        })
                                    setTransition(transition ?: TRANSIT_FRAGMENT_OPEN)
                                    replace(
                                        this@BaseFragment.id,
                                        overlay)
                                }
                            }
                        }
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        Scheduler.defer(::onViewCreated, Migration::class)
                }
            }
        } onCancel {
            // handle listening error
            State[1] = State.Failed
        }
        info(UI_TAG, "Main fragment view is created.")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        launch(Dispatchers.IO) @JobTreeRoot @MainViewGroup {
            trySafelyCanceling {
                with(context) {
                    buildAppDatabase()
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

    private var navigate = fun(_: View, bundle: Bundle?): Pair<Fragment, Int?> {
        if (bundle?.getShort(ACTION_KEY, -1) == ACTION_NAV_MAIN_UI)
            abstraction.module.Fragment(::screenEventInterceptor).apply {
                // ...
            }
        throw BaseImplementationRestriction
    }

    inline fun <reified R> screenEventInterceptor(
        listener: Any,
        callback: KFunction<R>,
        vararg param: Any?,
        r: Runnable?
    ): Pair<Predicate?, Boolean?>? = null

    override fun onDestroyView() {
        close(MainViewGroup::class)
        super.onDestroyView()
    }

    @Retention(SOURCE)
    @Target(EXPRESSION)
    protected annotation class MainViewGroup

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}