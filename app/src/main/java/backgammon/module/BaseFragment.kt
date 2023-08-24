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
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Dispatchers.IO
import backgammon.module.Scheduler.EventBus
import backgammon.module.Scheduler.FromLastCancellation
import backgammon.module.Scheduler.defer
import backgammon.module.Scheduler.Event.Listening
import backgammon.module.Scheduler.Event.Remitting
import backgammon.module.Scheduler.Path
import backgammon.module.State.Pending
import backgammon.module.State.Resolved
import backgammon.module.State.Succeeded
import backgammon.module.State.Suspending
import backgammon.module.State.Unresolved
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
        launch(LAZY) @MainViewGroup @Listening {
            EventBus.collectSafely {
                when (it?.transit) {
                    COMMIT_NAV_MAIN_UI -> {
                        transit(view, savedInstanceState) {
                            putShort(ACTION_KEY, COMMIT_NAV_MAIN_UI) }
                        State[1] = Succeeded
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        defer(::onViewCreated, Migration::class)
                }
            }
        } onError { job ->
            transit(view, savedInstanceState) {
                putShort(ACTION_KEY, ABORT_NAV_MAIN_UI) }
            State[1] += Pending
            keepAliveOrClose(job)
        } onTimeout {
            State[1] = Unresolved
            error(it)
        } then
            Job::join

        if (infoLogIsNotBypassed)
            info(UI_TAG, "Main fragment view is created.")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        launch(IO, LAZY) @JobTreeRoot @MainViewGroup @Remitting(
            delay = 100L,
            pathwise = [ FromLastCancellation::class ]
        ) @Path {
            context.tryCanceling(Context::buildAppDatabase)
        } then {
            context.change(Context::stageDbCreated)
        } given {
            db !== null
        } otherwise(
            SchedulerScope::retry
        ) then @Path {
            tryCancelingSuspended(::buildSession)
        } then {
            context.change(Context::stageSessionCreated)
        } given {
            session !== null
        } otherwise(
            SchedulerScope::retry
        ) onError {
            State[1] = Suspending
        } onCancel(
            SchedulerScope::retry
        ) then {
            enact(it) { err ->
                // catch cancellation and/or error
                when (err) {
                    is CancellationException -> State[1] += Suspending
                }
            }
        }
    }

    override fun onResume() {
        if (State[1] !is Resolved)
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