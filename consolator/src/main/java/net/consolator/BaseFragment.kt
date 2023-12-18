package net.consolator

import android.content.Context
import android.os.*
import android.view.*
import androidx.fragment.app.*
import androidx.fragment.app.FragmentTransaction.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlinx.coroutines.*
import net.consolator.Scheduler.EventBus
import net.consolator.Scheduler.FromLastCancellation
import net.consolator.Scheduler.Event.Listening
import net.consolator.Scheduler.Event.Remitting
import net.consolator.Scheduler.LaunchScope
import net.consolator.Scheduler.Path
import net.consolator.Scheduler.Path.Parallel
import net.consolator.Scheduler.Scope
import net.consolator.State.Pending
import net.consolator.State.Resolved
import net.consolator.State.Succeeded
import net.consolator.State.Suspending
import net.consolator.State.Unresolved
import net.consolator.application.*
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Dispatchers.IO
import net.consolator.BaseApplication.Companion.ACTION_MIGRATE_APP
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

abstract class BaseFragment : Fragment() {
    protected abstract var overlay: (View, Bundle?) -> Pair<Fragment?, Int?>
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
        launch(start = LAZY) @MainViewGroup @Listening {
            EventBus.collectSafely {
                when (it?.transit) {
                    COMMIT_NAV_MAIN_UI -> {
                        transit(view, savedInstanceState) {
                            putShort(ACTION_KEY, COMMIT_NAV_MAIN_UI) }
                        State[1] = Succeeded
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        defer<Migration>(::onViewCreated)
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

        if (info.isOn)
            info(UI_TAG, "Main fragment view is created.")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val context = context.weakRef()!!
        launch(IO, LAZY) @JobTreeRoot @MainViewGroup @Remitting(
            delay = 100L,
            pathwise = [ FromLastCancellation::class ]
        ) @LaunchScope @Parallel @Path("app-db.build") {
            registerContext(context)
            tryCancelingSuspended(retrieveContext(), Context::buildAppDatabase)
        } then @Scope {
            change(Context::stageDbCreated)
        } given {
            db !== null
        } otherwise(
            SchedulerScope::retry
        ) then @LaunchScope @Path("session.build") {
            tryCancelingSuspended(::buildSession)
        } then @Scope {
            change(Context::stageSessionCreated)
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
        defer<MemoryManager>(::onLowMemory) {
            super.onLowMemory()
        }
    }

    @Retention(SOURCE)
    @Target(EXPRESSION)
    protected annotation class MainViewGroup

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}