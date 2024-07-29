package net.consolator

import android.content.*
import android.os.*
import android.view.*
import androidx.fragment.app.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlinx.coroutines.*
import net.consolator.application.*
import net.consolator.Event.Committing
import net.consolator.Event.Listening
import net.consolator.Event.Listening.OnEvent
import net.consolator.Event.Retrying
import net.consolator.Path.Parallel
import net.consolator.State.Ambiguous
import net.consolator.State.Failed
import net.consolator.State.Resolved
import net.consolator.State.Succeeded
import net.consolator.State.Unresolved
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Dispatchers.IO
import net.consolator.BaseApplication.Companion.ACTION_MIGRATE_APP
import net.consolator.BaseApplication.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI
import net.consolator.Scheduler.applicationMemoryManager
import net.consolator.Scheduler.applicationMigrationManager

abstract class BaseFragment : Fragment(contentLayoutId) {
    internal lateinit var transit: (Short) -> Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (State[1] is Resolved) return
        launch(start = LAZY) @MainViewGroup @Listening
        @OnEvent(ACTION_MIGRATE_APP) {
            defer<MigrationManager>(::onViewCreated) }
        .otherwise @OnEvent(COMMIT_NAV_MAIN_UI) { _, _ ->
            transit(COMMIT_NAV_MAIN_UI)
            State[1] = Succeeded
            close(MainViewGroup::class) }
        .onError { job, _ ->
            transit(ABORT_NAV_MAIN_UI)
            State[1] = Failed
            keepAliveOrClose(job) }
        .onTimeout { job, _ ->
            State[1] = Unresolved
            error(job) }
        .then(
            CoroutineScope::enact
    ) }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (State[1] is Resolved) return
        val context = context.asWeakReference()
        launch(IO, LAZY) @JobTreeRoot @MainViewGroup @Retrying(
            delay = VIEW_MIN_DELAY,
            pathwise = [FromLastCancellation::class]
        ) @Tag(VIEW_ATTACH) {
            registerContext(context) }
        .then @Parallel @Path(STAGE_BUILD_APP_DB) { _, _ ->
            tryCancelingSuspended(::currentContext, Context::buildAppDatabase) }
        .then @Committing @Event(ACTION_MIGRATE_APP) { _, _ ->
            change(Context::stageAppDbCreated) }
        .given(
            Job::isAppDbCreated)
        .otherwise(
            CoroutineScope::retry)
        .then @Path(STAGE_BUILD_SESSION) { _, _ ->
            tryCancelingSuspended(::buildSession) }
        .then @Committing @Event(ACTION_MIGRATE_APP) { _, _ ->
            change(Context::stageSessionCreated) }
        .given(
            Job::isSessionCreated)
        .otherwise(
            CoroutineScope::retry)
        .onError { _, _ ->
            State[1] = Ambiguous }
        .onCancel(
            CoroutineScope::retry)
        .then { job, _ ->
            enact(job) { err ->
                // catch cancellation and/or error
                when (err) {
                    is CancellationException -> State[1] = Ambiguous
                } }
    } }

    override fun onStart() {
        super.onStart()
        if (foregroundLifecycleOwner === activity)
            foregroundLifecycleOwner = this
    }

    override fun onResume() {
        if (State[1] !is Resolved)
            reattach(MainViewGroup::class)
        super.onResume()
    }

    override fun onStop() {
        if (foregroundLifecycleOwner === this)
            foregroundLifecycleOwner = parentFragment ?: activity
        super.onStop()
    }

    override fun onDestroyView() {
        close(MainViewGroup::class)
        super.onDestroyView()
    }

    @Retention(SOURCE)
    @Target(EXPRESSION)
    internal annotation class MainViewGroup

    internal companion object {
        const val UI_TAG = "FRAGMENT"
    }
}