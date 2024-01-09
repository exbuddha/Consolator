package net.consolator

import android.content.Context
import android.os.*
import android.view.*
import androidx.fragment.app.*
import androidx.fragment.app.FragmentTransaction.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlinx.coroutines.*
import net.consolator.Event.Listening
import net.consolator.Event.Retrying
import net.consolator.Event.Signaling
import net.consolator.Path.Parallel
import net.consolator.Scheduler.EventBus
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
import net.consolator.Scheduler.applicationMemoryManager
import net.consolator.Scheduler.applicationMigrationResolver

abstract class BaseFragment : Fragment(contentLayoutId), ObjectProvider {
    protected abstract var overlay: (View, Bundle?) -> Pair<Fragment?, Int?>
    private inline fun transit(view: View, savedInstanceState: Bundle?, crossinline editor: BundleEditor) {
        val (overlay, transition) =
            overlay(view, savedInstanceState.reconstruct().apply { editor() })
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
        fun transit(action: Short) = transit(view, savedInstanceState) {
            putShort(ACTION_KEY, action) }
        launch(start = LAZY) @MainViewGroup @Listening {
            EventBus.collectSafely {
                when (it?.transit) {
                    COMMIT_NAV_MAIN_UI -> {
                        transit(COMMIT_NAV_MAIN_UI)
                        State[1] = Succeeded
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        defer<Migration>(::onViewCreated)
                }
            }
        } onError { job ->
            transit(ABORT_NAV_MAIN_UI)
            State[1] += Pending
            keepAliveOrClose(job)
        } onTimeout {
            State[1] = Unresolved
            error(it)
        } then
            Job::join
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val context = context.weakRef()!!
        launch(IO, LAZY) @JobTreeRoot @MainViewGroup @Retrying(
            delay = 100L,
            pathwise = [ FromLastCancellation::class ]
        ) @Tag("view.attach") {
            registerContext(context)
        } then @Parallel @Path(AppDatabase.STAGE_BUILD) {
            tryCancelingSuspended(retrieveContext(), Context::buildAppDatabase)
        } then @Signaling @Event(ACTION_MIGRATE_APP) {
            change(Context::stageDbCreated)
        } given {
            db !== null
        } otherwise(
            SchedulerScope::retry
        ) then @Path(RuntimeSessionEntity.STAGE_BUILD) {
            tryCancelingSuspended(::buildSession)
        } then @Signaling @Event(ACTION_MIGRATE_APP) {
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

    override fun invoke(type: AnyKClass) = when (type) {
        Migration::class ->
            ::applicationMigrationResolver.requireAsync(constructor = ::Migration)!!
        MemoryManager::class ->
            ::applicationMemoryManager.require(constructor = ::MemoryManager)!!
        else ->
            throw BaseImplementationRestriction
    }

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}