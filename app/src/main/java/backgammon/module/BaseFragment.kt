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
import backgammon.module.Scheduler.EventBus
import backgammon.module.Scheduler.FromLastCancellation
import backgammon.module.Scheduler.defer
import backgammon.module.Scheduler.Event.Listening
import backgammon.module.Scheduler.Event.Remitting
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP
import backgammon.module.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import backgammon.module.BaseApplication.Companion.COMMIT_NAV_MAIN_UI
import backgammon.module.application.*

abstract class BaseFragment : Fragment() {
    protected abstract var overlay: (View, Bundle?) -> Pair<out Fragment?, Int?>
    private inline fun transit(view: View, savedInstanceState: Bundle?, crossinline editor: BundleEditor) {
        val (overlay, transition) =
            overlay(view, (savedInstanceState ?: Bundle()).apply { editor() })
        if (overlay !== null)
            schedule {
                parentFragmentManager.commit {
                    setTransition(transition ?: TRANSIT_FRAGMENT_OPEN)
                    replace(
                        this@BaseFragment.id,
                        overlay)
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launch @MainViewGroup @Listening {
            EventBus.collectSafely {
                when (it?.transit) {
                    COMMIT_NAV_MAIN_UI -> {
                        transit(view, savedInstanceState) {
                            putShort(ACTION_KEY, COMMIT_NAV_MAIN_UI) }
                        State[1] = State.Succeeded
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        defer(::onViewCreated, Migration::class)
                }
            }
        } onError { job ->
            transit(view, savedInstanceState) {
                putShort(ACTION_KEY, ABORT_NAV_MAIN_UI) }
            State[1] += State.Pending
            keepAliveOrClose(MainViewGroup::class, job)
        } then(SchedulerScope::enact)
        if (infoLogIsNotBypassed)
            info(UI_TAG, "Main fragment view is created.")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        launch(Dispatchers.IO) @JobTreeRoot @MainViewGroup
        @Remitting(delay = 100L, pathwise = [ FromLastCancellation::class ]) {
            context.tryCanceling {
                buildAppDatabase()
                event(Context::stageDbCreated)
            }
        } then {
            context.tryCanceling {
                buildSession()
                event(Context::stageSessionCreated)
            }
        } onError {
            State[1] = State.Suspending
        } onCancel {
            retry(it)
        } then {
            enact(it) { err ->
                // catch cancellation and/or error
                when (err) {
                    CancellationException::class -> {}
                    else -> {}
                }
            }
        }
    }

    override fun onResume() {
        reattach(MainViewGroup::class)
        super.onResume()
    }

    override fun onDestroyView() {
        close(MainViewGroup::class)
        super.onDestroyView()
    }

    override fun onLowMemory() {
        defer(::onLowMemory, MemoryManager::class, { super.onLowMemory() })
    }

    @Retention(SOURCE)
    @Target(EXPRESSION)
    protected annotation class MainViewGroup

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}