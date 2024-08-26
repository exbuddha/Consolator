@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package net.consolator

import android.os.*
import android.view.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import iso.consolator.*
import iso.consolator.fragment.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN
import kotlinx.coroutines.Dispatchers.Default
import net.consolator.BaseActivity.Companion.ABORT_NAV_MAIN_UI
import net.consolator.BaseActivity.Companion.COMMIT_NAV_MAIN_UI

@Tag(MAIN_FRAGMENT)
open class MainFragment : BaseFragment(), Provider {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (this is OverlayFragment) return
        if (savedInstanceState === null) {
            // show animation or progress bar
            parentFragmentManager.commit {
                show(this@MainFragment) }
            currentThread.log(info, VIEW_TAG, "Main fragment view is created.") }
    }

    override fun commit(source: Short, destination: Short) { when (destination) {
        COMMIT_NAV_MAIN_UI ->
            schedule @Ahead {
            parentFragmentManager.commit {
                setTransition(TRANSIT_FRAGMENT_OPEN)
                replace(
                    this@MainFragment.id,
                    OverlayFragment(
                        this@MainFragment, ::viewEventInterceptor)
                        .apply {
                        /* renew main view */
                        }) } }
        ABORT_NAV_MAIN_UI -> {
            /* continue animation or alternatives */ }
        else ->
            throw BaseImplementationRestriction()
    } }

    protected inline fun <reified R> viewEventInterceptor(listener: Any, callback: KFunction<R>, vararg args: Any?, noinline postback: PostbackFunction?): Interception =
        (null to false)
        .also { interception ->
        postback?.let { postback ->
        if (postback.asReference().isScheduledAhead)
            runBlocking { postback(interception) }
        else
            lifecycleScope.launch(Default) { postback(interception) } } }

    override fun invoke(type: AnyKClass) = when (type) {
        TransitionManager::class ->
            this
        MigrationManager::class ->
            MigrationManager()
        else ->
            activity.asObjectProvider()!!(type) }

    override fun <R> invoke(vararg tag: TagType): KCallable<R> = activity.asFunctionProvider()!!(*tag)
}