@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package net.consolator

import android.content.*
import android.os.*
import android.view.*
import androidx.annotation.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import iso.consolator.*
import iso.consolator.Delay
import iso.consolator.Event.*
import iso.consolator.Event.Listening.*
import iso.consolator.Path.*
import iso.consolator.State.*
import iso.consolator.fragment.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Dispatchers.IO
import net.consolator.BaseActivity.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseActivity.Companion.COMMIT_NAV_MAIN_UI
import net.consolator.BaseApplication.Companion.ACTION_MIGRATE_APP

@LayoutRes
internal var contentLayoutId = R.layout.background

abstract class BaseFragment : Fragment(contentLayoutId), TransitionManager {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (State[1] is Resolved) return
        trySafely { (this as LifecycleOwner)
        .launch(start = LAZY) @MainViewGroup
        @Listening @OnEvent(ACTION_MIGRATE_APP) {
            defer<MigrationManager>(Fragment::onViewCreated, this@BaseFragment, {
                // listen to db updates
                // preload data
                // reset function pointers
                // repeat until stable
                EventBus.commit(COMMIT_NAV_MAIN_UI)
            }) }
        .otherwise @OnEvent(COMMIT_NAV_MAIN_UI) { _, _ ->
            defer<TransitionManager>(Fragment::onViewCreated, this@BaseFragment,
                fun(_: TransitionManager) {
                    State[1] = Succeeded
                    close(MainViewGroup::class)
                    transit(COMMIT_NAV_MAIN_UI) }) }
        .onError { _, job ->
            defer<TransitionManager>(Fragment::onViewCreated, this@BaseFragment,
                fun(_: TransitionManager) {
                    State[1] = Failed
                    keepAliveOrClose(job)
                    transit(ABORT_NAV_MAIN_UI) }) }
        .onTimeout { _, job ->
            State[1] = Unresolved
            error(job) }
        .then(
            CoroutineScope::enact
    ) } }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (State[1] is Resolved) return
        val context = context.asWeakReference()
        trySafely { (this as LifecycleOwner)
        .launch(@JobTreeRoot IO, LAZY) @MainViewGroup
        @Retrying @Pathwise([ FromLastCancellation::class ])
        @Delay(view_min_delay)
        @WithContext @Tag(VIEW_ATTACH) {
            context } // auto-register
        .then @Parallel @Path("$STAGE_BUILD_APP_DB") { _, _ ->
            tryCancelingSuspended(::currentContext, Context::buildAppDatabase) }
        .then @Committing @Event(ACTION_MIGRATE_APP) { _, _ ->
            change(Context::stageAppDbCreated) }
        .given(
            Job::isAppDbCreated)
        .otherwise(
            CoroutineScope::retry)
        .then @Path("$STAGE_BUILD_SESSION") { _, _ ->
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
        .then { _, job ->
            enact(job) { err ->
                // catch cancellation and/or error
                when (err) {
                    is CancellationException -> State[1] = Ambiguous
                } }
    } } }

    override fun onStart() {
        super.onStart()
        if (foregroundLifecycleOwner === activity)
            foregroundLifecycleOwner = this
        commitStart()
    }

    override fun onResume() {
        if (State[1] !is Resolved)
            reattach(MainViewGroup::class)
        super.onResume()
        commitResume()
    }

    override fun onPause() {
        super.onPause()
        commitPause()
    }

    override fun onStop() {
        commitStop()
        if (foregroundLifecycleOwner === this)
            foregroundLifecycleOwner = parentFragment ?: activity
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        close(MainViewGroup::class)
        commitDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // write to bundle
        commitSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun transit(destination: Short) = commit(destination = destination)

    override fun commit(vararg context: Any?) =
        context.lastOrNull()?.asTransitFunction()?.invoke(this)

    protected inner class MigrationManager : iso.consolator.fragment.MigrationManager()

    @Retention(SOURCE)
    @Target(EXPRESSION)
    protected annotation class MainViewGroup

    protected companion object {
        const val VIEW_TAG = "FRAGMENT"
    }
}