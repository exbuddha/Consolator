package net.consolator

import android.content.*
import android.os.*
import android.os.Process
import androidx.lifecycle.*
import java.lang.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.flow.*
import okhttp3.*
import net.consolator.BaseService.*
import net.consolator.BaseActivity.*
import net.consolator.Scheduler.EventBus
import net.consolator.Scheduler.EventBus.Relay
import net.consolator.Scheduler.Lock
import net.consolator.Scheduler.Sequencer
import net.consolator.application.*

inline fun <reified T : Deferral, R> Context.defer(member: KCallable<R>) =
    Scheduler.defer(member, T::class, this)
inline fun <reified T : Deferral> Context.defer(member: KFunction<Unit>, vararg value: Any?, noinline `super`: Work) =
    Scheduler.defer(member, T::class, this, *value, `super`)
inline fun <reified T : Deferral> Context.work(vararg id: Any?, noinline work: Work) =
    Scheduler.work(this, T::class, *id, work = work)
inline fun <reified T : Deferral> Context.step(vararg id: Any?, noinline step: CoroutineStep? = null) =
    work<T>(*id) { Scheduler.step(this, T::class, *id, step = step) }

interface SchedulerScope : CoroutineScope {
    override val coroutineContext
        get() = Scheduler
}

private interface SchedulerKey : CoroutineContext.Key<SchedulerElement>
private interface SchedulerElement : CoroutineContext.Element
private lateinit var _key: SchedulerKey
private val _element by lazy {
    object : SchedulerElement {
        override val key
            get() = _key
    }
}

object Scheduler : MutableLiveData<Step?>(), SchedulerScope, CoroutineContext, StepObserver, (SchedulerWork) -> Unit {
    fun <T : Deferral, R> defer(member: KCallable<R>, resolver: KClass<out T>, vararg value: Any?): Unit? =
        when (resolver) {
            Migration::class ->
                ::applicationMigrationResolver.setResolverThenCommit(value[0]!!)
            else -> when (member.javaClass.enclosingClass) {
                BaseService::class.java -> {
                    fun ResolverKProperty.setResolverThenCommit() = setResolverThenCommit(value[0]!!)
                    when (resolver) {
                        StartCommandResolver::class ->
                            ::serviceOnStartCommandResolver.setResolverThenCommit()
                        BindResolver::class ->
                            ::serviceOnBindResolver.setResolverThenCommit()
                        else ->
                            throw BaseImplementationRestriction
                    }
                }
                BaseActivity::class.java -> {
                    fun ResolverKProperty.setResolverThenResolve() = setResolverThenResolve(value[0]!!)
                    when (resolver) {
                        ConfigurationChangeManager::class ->
                            ::activityConfigurationChangeManager.setResolverThenResolve()
                        NightModeChangeManager::class ->
                            ::activityNightModeChangeManager.setResolverThenResolve()
                        LocalesChangeManager::class ->
                            ::activityLocalesChangeManager.setResolverThenResolve()
                        else ->
                            throw BaseImplementationRestriction
                    }
                }
                else -> throw BaseImplementationRestriction
            }
        }
    fun work(vararg id: Any?, work: Work) {
        fun Resolver.assignWork() = assignWork(work, id)
        when (id[0]) {
            is BaseServiceScope -> when (id[1]) {
                StartCommandResolver::class ->
                    serviceOnStartCommandResolver!!.assignWork()
                BindResolver::class ->
                    serviceOnBindResolver!!.assignWork()
                else ->
                    throw BaseImplementationRestriction
            }
            is BaseActivity -> when (id[1]) {
                ConfigurationChangeManager::class ->
                    activityConfigurationChangeManager!!.assignWork()
                NightModeChangeManager::class ->
                    activityNightModeChangeManager!!.assignWork()
                LocalesChangeManager::class ->
                    activityLocalesChangeManager!!.assignWork()
                else ->
                    throw BaseImplementationRestriction
            }
            else -> throw BaseImplementationRestriction
        }
        work()
    }
    fun step(vararg context: Any?, step: CoroutineStep?) {
        if (context.isNotEmpty())
            when (val scope = context[0]) {
                is BaseServiceScope -> {
                    if (step !== null)
                        clockAhead {
                            step(
                                context.lastOrNull()?.asType() ?:
                                trySafelyForAnnotatedScopeOf(step) ?:
                                scope)
                        }
                    return
                }
                is BaseActivity -> {
                    fun Resolver.assignStepThenResolve() = assignStepThenResolve(step)
                    when (context[1]) {
                        ConfigurationChangeManager::class ->
                            activityConfigurationChangeManager!!.assignStepThenResolve()
                        NightModeChangeManager::class -> {
                            activityNightModeChangeManager!!.assignStepThenResolve()
                            activityNightModeChangeManager = null
                        }
                        LocalesChangeManager::class -> {
                            activityLocalesChangeManager!!.assignStepThenResolve()
                            activityLocalesChangeManager = null
                        }
                        else ->
                            throw BaseImplementationRestriction
                    }
                    return
                }
            }
        else if (step !== null) {
            clock {
                annotatedScopeOf(step).step()
            }
            return
        }
        throw BaseImplementationRestriction
    }

    var serviceOnStartCommandResolver: StartCommandResolver? = null
    var serviceOnBindResolver: BindResolver? = null
        private set
    var activityConfigurationChangeManager: ConfigurationChangeManager? = null
        private set
    var activityNightModeChangeManager: NightModeChangeManager? = null
        private set
    var activityLocalesChangeManager: LocalesChangeManager? = null
        private set
    var applicationMigrationResolver: Migration? = null
    private fun ResolverKProperty.setResolverThenCommit(provider: Any) =
        (reconstruct(provider) as? Deferral)?.commit()
    private fun ResolverKProperty.setResolverThenResolve(provider: Any) =
        (reconstruct(provider) as? Resolver)?.resolve(provider)
    private fun ResolverKProperty.reconstruct(provider: Any) =
        reconstruct(this::class, provider, null)

    open class Clock(
        name: String? = null,
        priority: Int = currentThread.priority
    ) : HandlerThread(name, priority) {
        override fun start() {
            commitAsyncIfNotAlive {
                super.start()
                queue = java.util.LinkedList()
            }
        }
        fun alsoStart(): Clock {
            start()
            return this
        }

        var handler: Handler? = null
        private var queue: RunnableList? = null
        override fun run() {
            handler = object : Handler(looper) {
                override fun handleMessage(msg: Message) {
                    super.handleMessage(msg)
                    turn(msg)
                }
            }
            queue?.run()
        }
        private fun turn(msg: Message) {
            queue?.run()
            msg.callback.run()
        }
        private fun RunnableList.run() {
            onEach {
                synchronized(sLock) {
                    it.run()
                    remove(it)
                }
            }
        }

        private var hLock = Lock.Open
        fun <R> commit(block: () -> R) = synchronized(hLock) {
            hLock = Lock.Closed()
            block()
            hLock = Lock.Open()
        }
        private fun <R> commitAsyncIfNotAlive(block: () -> R) =
            commitAsync(hLock, { !isAlive }, block)

        private var sLock = Any()
        var post = fun(callback: Runnable) =
            handler?.post(callback) ?:
            synchronized(sLock) { queue!!.add(callback) }
        var postAhead = fun(callback: Runnable) =
            handler?.postAtFrontOfQueue(callback) ?:
            synchronized(sLock) { queue!!.add(0, callback) }

        fun clearObjects() {
            handler = null
            queue = null
        }
        companion object
    }

    class Sequencer {
        fun io(async: Boolean = false, step: SequencerStep) = attach(IO, async, step)
        fun io(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, IO, async, step)
        fun ioStart(step: SequencerStep) = io(false, step).also { start() }
        fun ioResume(async: Boolean = false, step: SequencerStep) = io(async, step).also { resume() }
        fun ioAfter(async: Boolean = false, step: SequencerStep) = attachAfter(IO, async, step)
        fun ioBefore(async: Boolean = false, step: SequencerStep) = attachBefore(IO, async, step)
        fun ioResettingFirstly(async: Boolean = false, step: SequencerStep) = io(async, resettingFirstly(step))
        fun ioResettingLastly(async: Boolean = false, step: SequencerStep) = io(async, resettingLastly(step))
        fun ioResettingFirstly(index: Int, async: Boolean = false, step: SequencerStep) = io(index, async, resettingFirstly(step))
        fun ioResettingLastly(index: Int, async: Boolean = false, step: SequencerStep) = io(index, async, resettingLastly(step))
        fun ioStartResettingFirstly(step: SequencerStep) = io(false, resettingFirstly(step)).also { start() }
        fun ioStartResettingLastly(step: SequencerStep) = io(false, resettingLastly(step)).also { start() }
        fun ioResumeResettingFirstly(async: Boolean = false, step: SequencerStep) = io(async, resettingFirstly(step)).also { resume() }
        fun ioResumeResettingLastly(async: Boolean = false, step: SequencerStep) = io(async, resettingLastly(step)).also { resume() }
        fun ioAfterResettingFirstly(async: Boolean = false, step: SequencerStep) = ioAfter(async, resettingFirstly(step))
        fun ioAfterResettingLastly(async: Boolean = false, step: SequencerStep) = ioAfter(async, resettingLastly(step))
        fun ioBeforeResettingFirstly(async: Boolean = false, step: SequencerStep) = ioBefore(async, resettingFirstly(step))
        fun ioBeforeResettingLastly(async: Boolean = false, step: SequencerStep) = ioBefore(async, resettingLastly(step))

        fun main(async: Boolean = false, step: SequencerStep) = attach(Main, async, step)
        fun main(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Main, async, step)
        fun mainStart(step: SequencerStep) = main(false, step).also { start() }
        fun mainResume(async: Boolean = false, step: SequencerStep) = main(async, step).also { resume() }
        fun mainAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Main, async, step)
        fun mainBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Main, async, step)
        fun mainResettingFirstly(async: Boolean = false, step: SequencerStep) = main(async, resettingFirstly(step))
        fun mainResettingLastly(async: Boolean = false, step: SequencerStep) = main(async, resettingLastly(step))
        fun mainResettingFirstly(index: Int, async: Boolean = false, step: SequencerStep) = main(index, async, resettingFirstly(step))
        fun mainResettingLastly(index: Int, async: Boolean = false, step: SequencerStep) = main(index, async, resettingLastly(step))
        fun mainStartResettingFirstly(step: SequencerStep) = main(false, resettingFirstly(step)).also { start() }
        fun mainStartResettingLastly(step: SequencerStep) = main(false, resettingLastly(step)).also { start() }
        fun mainResumeResettingFirstly(async: Boolean = false, step: SequencerStep) = main(async, resettingFirstly(step)).also { resume() }
        fun mainResumeResettingLastly(async: Boolean = false, step: SequencerStep) = main(async, resettingLastly(step)).also { resume() }
        fun mainAfterResettingFirstly(async: Boolean = false, step: SequencerStep) = mainAfter(async, resettingFirstly(step))
        fun mainAfterResettingLastly(async: Boolean = false, step: SequencerStep) = mainAfter(async, resettingLastly(step))
        fun mainBeforeResettingFirstly(async: Boolean = false, step: SequencerStep) = mainBefore(async, resettingFirstly(step))
        fun mainBeforeResettingLastly(async: Boolean = false, step: SequencerStep) = mainBefore(async, resettingLastly(step))

        fun default(async: Boolean = false, step: SequencerStep) = attach(Default, async, step)
        fun default(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Default, async, step)
        fun defaultStart(step: SequencerStep) = default(false, step).also { start() }
        fun defaultResume(async: Boolean = false, step: SequencerStep) = default(async, step).also { resume() }
        fun defaultAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Default, async, step)
        fun defaultBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Default, async, step)
        fun defaultResettingFirstly(async: Boolean = false, step: SequencerStep) = default(async, resettingFirstly(step))
        fun defaultResettingLastly(async: Boolean = false, step: SequencerStep) = default(async, resettingLastly(step))
        fun defaultResettingFirstly(index: Int, async: Boolean = false, step: SequencerStep) = default(index, async, resettingFirstly(step))
        fun defaultResettingLastly(index: Int, async: Boolean = false, step: SequencerStep) = default(index, async, resettingLastly(step))
        fun defaultStartResettingFirstly(step: SequencerStep) = default(false, resettingFirstly(step)).also { start() }
        fun defaultStartResettingLastly(step: SequencerStep) = default(false, resettingLastly(step)).also { start() }
        fun defaultResumeResettingFirstly(async: Boolean = false, step: SequencerStep) = default(async, resettingFirstly(step)).also { resume() }
        fun defaultResumeResettingLastly(async: Boolean = false, step: SequencerStep) = default(async, resettingLastly(step)).also { resume() }
        fun defaultAfterResettingFirstly(async: Boolean = false, step: SequencerStep) = defaultAfter(async, resettingFirstly(step))
        fun defaultAfterResettingLastly(async: Boolean = false, step: SequencerStep) = defaultAfter(async, resettingLastly(step))
        fun defaultBeforeResettingFirstly(async: Boolean = false, step: SequencerStep) = defaultBefore(async, resettingFirstly(step))
        fun defaultBeforeResettingLastly(async: Boolean = false, step: SequencerStep) = defaultBefore(async, resettingLastly(step))

        fun unconfined(async: Boolean = false, step: SequencerStep) = attach(Unconfined, async, step)
        fun unconfined(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Unconfined, async, step)
        fun unconfinedStart(step: SequencerStep) = unconfined(false, step).also { start() }
        fun unconfinedResume(async: Boolean = false, step: SequencerStep) = unconfined(async, step).also { resume() }
        fun unconfinedAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Unconfined, async, step)
        fun unconfinedBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Unconfined, async, step)
        fun unconfinedResettingFirstly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingFirstly(step))
        fun unconfinedResettingLastly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingLastly(step))
        fun unconfinedResettingFirstly(index: Int, async: Boolean = false, step: SequencerStep) = unconfined(index, async, resettingFirstly(step))
        fun unconfinedResettingLastly(index: Int, async: Boolean = false, step: SequencerStep) = unconfined(index, async, resettingLastly(step))
        fun unconfinedStartResettingFirstly(step: SequencerStep) = unconfined(false, resettingFirstly(step)).also { start() }
        fun unconfinedStartResettingLastly(step: SequencerStep) = unconfined(false, resettingLastly(step)).also { start() }
        fun unconfinedResumeResettingFirstly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingFirstly(step)).also { resume() }
        fun unconfinedResumeResettingLastly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingLastly(step)).also { resume() }
        fun unconfinedAfterResettingFirstly(async: Boolean = false, step: SequencerStep) = unconfinedAfter(async, resettingFirstly(step))
        fun unconfinedAfterResettingLastly(async: Boolean = false, step: SequencerStep) = unconfinedAfter(async, resettingLastly(step))
        fun unconfinedBeforeResettingFirstly(async: Boolean = false, step: SequencerStep) = unconfinedBefore(async, resettingFirstly(step))
        fun unconfinedBeforeResettingLastly(async: Boolean = false, step: SequencerStep) = unconfinedBefore(async, resettingLastly(step))

        private fun mark(step: SequencerStep) =
            step.apply { asCallable().markTag() }

        private constructor(observer: StepObserver) { this.observer = observer }
        constructor() : this(Scheduler)

        private val observer: StepObserver
        private var seq: LiveSequence = mutableListOf()
        private var ln = -1
        private val work
            get() = seq[ln]
        private var latestStep: LiveStep? = null
        private var latestCapture: Any? = null

        private fun init() {
            ln = -1
            clearFlags()
            clearObjects()
        }
        fun start() {
            init()
            `continue`()
        }
        fun resume(index: Int = ln) {
            ln = index
            `continue`()
        }
        fun retry() {
            ln -= 1
            `continue`()
        }
        private fun `continue`() {
            isActive = true
            advance()
        }
        private fun prepare() {
            if (ln < -1) ln = -1
        }
        private fun jump(index: Int = ++ln) =
            if (hasError) null
            else (index < seq.size && (!isObserving || seq[index].third)).also {
                if (it) ln = index
            }
        private fun advance() {
            prepare()
            while (jump() ?: return)
                work.let { observe(it) ?: capture(it) } || return
            end()
        }
        private fun observe(work: LiveWork): Boolean? {
            val (step, _, async) = work
            try {
                step().let { step ->
                    latestStep = step
                    step?.observeForever(observer) ?:
                    return null
                }
            } catch (ex: Throwable) {
                hasError = true
                this.ex = ex
                return false
            }
            isObserving = true
            return async
        }
        private fun capture(work: LiveWork): Boolean {
            work.second.let { capture ->
                latestCapture = capture
                capture?.invoke()?.let { async ->
                    if (async is Boolean) return async
                }
            }
            return false
        }
        fun reset(step: LiveStep? = latestStep) {
            step?.removeObserver(observer)
            isObserving = false
        }
        fun end() {
            if (!isObserving)
                isCompleted = true
        }
        private fun resettingFirstly(step: SequencerStep) = SequencerScope::reset then step
        private fun resettingLastly(step: SequencerStep) = step then SequencerScope::reset

        var isActive = false
        var isObserving = false
        var isCompleted = false
        var isCancelled = false
        var hasError = false
        var ex: Throwable? = null
        fun clearFlags() {
            isActive = false
            isObserving = false
            isCompleted = false
            isCancelled = false
            hasError = false
            ex = null
        }
        fun clearObjects() {
            seq.clear()
            latestStep = null
            latestCapture = null
        }
        companion object;

        private fun LiveSequence.attach(element: LiveWork) =
            add(element)
        private fun LiveSequence.attach(index: Int, element: LiveWork) =
            add(index, element)
        fun attach(work: LiveWork) {
            seq.attach(work)
        }
        fun attachOnce(work: LiveWork) {
            if (work.isNotAttached())
                attach(work)
        }
        fun attachOnce(range: IntRange, work: LiveWork) {
            if (work.isNotAttached(range))
                attach(work)
        }
        fun attachOnce(first: Int, last: Int, work: LiveWork) {
            if (work.isNotAttached(first, last))
                attach(work)
        }
        fun attach(index: Int, work: LiveWork) {
            with(seq) {
                if (ln in index..size)
                    ln += 1
                attach(index, work)
            }
        }
        fun attachOnce(index: Int, work: LiveWork) {
            if (work.isNotAttached(index))
                seq.attach(index, work)
        }
        fun attachOnce(range: IntRange, index: Int, work: LiveWork) {
            if (work.isNotAttached(range, index))
                attach(index, work)
        }
        fun attachOnce(first: Int, last: Int, index: Int, work: LiveWork) {
            if (work.isNotAttached(first, last, index))
                attach(index, work)
        }
        fun attachAfter(work: LiveWork) {
            attach(after, work)
        }
        fun attachBefore(work: LiveWork) {
            attach(before, work)
        }
        fun attachOnceAfter(work: LiveWork) {
            attachOnce(after, work)
        }
        fun attachOnceBefore(work: LiveWork) {
            attachOnce(before, work)
        }
        fun attach(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = mark(step)) }.also { attach(it) }
        fun attach(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = mark(step)) }, capture, async).also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = mark(step)) }.also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = mark(step)) }, capture, async).also { attach(it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = mark(step)) }.also { attach(index, it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = mark(step)) }, capture, async).also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = mark(step)) }.also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = mark(step)) }, capture, async).also { attach(index, it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = mark(step)) }.also { attachAfter(it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = mark(step)) }, capture, async).also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = mark(step)) }.also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = mark(step)) }, capture, async).also { attachAfter(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = mark(step)) }.also { attachBefore(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = mark(step)) }, capture, async).also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = mark(step)) }.also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = mark(step)) }, capture, async).also { attachBefore(it) }

        fun capture(block: CaptureFunction) {
            attach(nullStepTo(block))
        }
        fun captureOnce(block: CaptureFunction) {
            if (block.isNotAttached())
                capture(block)
        }
        fun captureOnce(range: IntRange, block: CaptureFunction) {
            if (block.isNotAttached(range))
                capture(block)
        }
        fun captureOnce(first: Int, last: Int, block: CaptureFunction) {
            if (block.isNotAttached(first, last))
                capture(block)
        }
        fun capture(index: Int, block: CaptureFunction) {
            attach(index, nullStepTo(block))
        }
        fun captureAfter(block: CaptureFunction) {
            attachAfter(nullStepTo(block))
        }
        fun captureBefore(block: CaptureFunction) {
            attachBefore(nullStepTo(block))
        }
        fun captureOnce(index: Int, block: CaptureFunction) {
            if (block.isNotAttached(index))
                capture(index, block)
        }
        fun captureOnce(range: IntRange, index: Int, block: CaptureFunction) {
            if (block.isNotAttached(range, index))
                capture(block)
        }
        fun captureOnce(first: Int, last: Int, index: Int, block: CaptureFunction) {
            if (block.isNotAttached(first, last, index))
                capture(block)
        }
        fun captureOnceAfter(block: CaptureFunction) {
            captureOnce(after, block)
        }
        fun captureOnceBefore(block: CaptureFunction) {
            captureOnce(before, block)
        }
        private fun nullStepTo(block: CaptureFunction) = Triple(nullStep, block, false)
        private fun stepToNull(async: Boolean = false, step: LiveStepFunction) = Triple(step, nullBlock, async)
        private val nullStep: LiveStepFunction = { null }
        private val nullBlock: CaptureFunction? = null

        private fun LiveWork.isSameWork(work: LiveWork) =
            this === work || (first === work.first && second === work.second)
        private fun LiveWork.isNotSameWork(work: LiveWork) =
            this !== work || first !== work.first || second != work.second
        private fun LiveWork.isSameCapture(block: CaptureFunction) =
            second === block
        private fun LiveWork.isNotSameCapture(block: CaptureFunction) =
            second !== block

        private fun LiveWork.isNotAttached() =
            seq.noneReversed { it.isSameWork(this) }
        private fun LiveWork.isNotAttached(range: IntRange): Boolean {
            range.forEach {
                if (seq[it].isSameWork(this))
                    return false
            }
            return true
        }
        private fun LiveWork.isNotAttached(first: Int, last: Int): Boolean {
            for (i in first..last)
                if (seq[i].isSameWork(this))
                    return false
            return true
        }
        private fun LiveWork.isNotAttached(index: Int) =
            none(index) { it.isSameWork(this) }
        private fun LiveWork.isNotAttached(range: IntRange, index: Int) = when {
            range.isEmpty() -> true
            index - range.first <= range.last - index ->
                range.none { seq[it].isSameWork(this) }
            else ->
                range.noneReversed { seq[it].isSameWork(this) }
        }
        private fun LiveWork.isNotAttached(first: Int, last: Int, index: Int) = when {
            first < last -> true
            index - first <= last - index ->
                seq.none { it.isSameWork(this) }
            else ->
                seq.noneReversed { it.isSameWork(this) }
        }
        private fun CaptureFunction.isNotAttached() =
            seq.none { it.isSameCapture(this) }
        private fun CaptureFunction.isNotAttached(range: IntRange): Boolean {
            range.forEach {
                if (seq[it].isSameCapture(this))
                    return false
            }
            return true
        }
        private fun CaptureFunction.isNotAttached(first: Int, last: Int): Boolean {
            for (i in first..last)
                if (seq[i].isSameCapture(this))
                    return false
            return true
        }
        private fun CaptureFunction.isNotAttached(index: Int) =
            none(index) { it.isSameCapture(this) }
        private fun CaptureFunction.isNotAttached(range: IntRange, index: Int) = when {
            range.isEmpty() -> true
            index - range.first <= range.last - index ->
                range.none { seq[it].isSameCapture(this) }
            else ->
                range.noneReversed { seq[it].isSameCapture(this) }
        }
        private fun CaptureFunction.isNotAttached(first: Int, last: Int, index: Int) = when {
            first < last -> true
            index - first <= last - index ->
                seq.none { it.isSameCapture(this) }
            else ->
                seq.noneReversed { it.isSameCapture(this) }
        }
        private inline fun none(index: Int, predicate: LiveWorkPredicate) = with(seq) {
            when {
                index < size / 2 ->
                    none(predicate)
                else ->
                    noneReversed(predicate)
            }
        }
        private inline fun LiveSequence.noneReversed(predicate: LiveWorkPredicate): Boolean {
            if (size == 0) return true
            for (i in (size - 1) downTo 0)
                if (predicate(this[i]))
                    return false
            return true
        }
        private inline fun IntRange.noneReversed(predicate: IntPredicate): Boolean {
            reversed().forEach {
                if (predicate(it))
                    return false
            }
            return true
        }

        val leading
            get() = 0 until with(seq) { if (ln < size) ln else size }
        val trailing
            get() = (if (ln < 0) 0 else ln + 1) until seq.size

        private val before
            get() = when {
                ln <= 0 -> 0
                ln < seq.size -> ln - 1
                else -> seq.size
            }
        private val after
            get() = when {
                ln < 0 -> 0
                ln < seq.size -> ln + 1
                else -> seq.size
            }
    }

    fun observe() { observeForever(this) }
    fun observeAsync() = commitAsync(Scheduler, { !Scheduler.hasObservers() }) {
        observe()
    }
    fun observe(owner: LifecycleOwner) = observe(owner, this)
    fun ignore() { removeObserver(this) }
    val observeScheduler = Runnable(::observe)
    val ignoreScheduler = Runnable(::ignore)
    val startSequencer = Runnable { sequencer?.start() }
    val resumeSequencer = Runnable { sequencer?.resume() }
    val retrySequencer = Runnable { sequencer?.retry() }

    var clock: Clock? = null
        get() = commitAsyncForResult(Clock, { field === null }, field) {
            Clock()
        }
    var sequencer: Sequencer? = null
        get() = commitAsyncForResult(Sequencer, { field === null }, field) {
            Sequencer()
        }

    fun windDownClock() {
        clock?.apply {
            Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_DEFAULT)
        }
    }
    fun windDown() {
        windDownClock()
        sequencer = null
    }

    fun clearResolverObjects() {
        serviceOnStartCommandResolver = null
        serviceOnBindResolver = null
        activityConfigurationChangeManager = null
        activityNightModeChangeManager = null
        activityLocalesChangeManager = null
        applicationMigrationResolver = null
    }
    fun clearObjects() {
        clock = null
        sequencer = null
    }

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class LaunchScope
    private val KCallable<*>.launchScope
        get() = annotations.find { it is LaunchScope } as? LaunchScope
    private fun annotatedLaunchScopeOf(step: CoroutineStep?) =
        step!!.asCallable().launchScope!!
    fun trySafelyForAnnotatedLaunchScopeOf(step: CoroutineStep?) =
        trySafelyForResult { annotatedLaunchScopeOf(step) }

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class Scope(val type: KClass<out CoroutineScope> = SchedulerScope::class)
    private val KCallable<*>.schedulerScope
        get() = annotations.find { it is Scope } as? Scope
    private fun annotatedScopeOf(step: CoroutineStep?) =
        step!!.asCallable().schedulerScope!!.type.reconstruct(step)
    fun trySafelyForAnnotatedScopeOf(step: CoroutineStep?) =
        trySafelyForResult { annotatedScopeOf(step) }

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class Path(
        val name: String = "",
        val route: SchedulerPath = [],
        val blacklist: SchedulerPath = []) {
        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Adjacent(val paths: Array<String> = [])

        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Converging(val paths: Array<String> = [])

        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Diverging(val paths: Array<String> = [])

        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Parallel(val paths: Array<String> = [])

        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Preceding(val paths: Array<String> = [])

        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
        annotation class Proceeding(val paths: Array<String> = [])
    }
    object FromLastCancellation : Throwable()
    object Propagate : Throwable()

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class Event(val transit: Short = 0) {
        @Retention(SOURCE)
        @Target(FUNCTION, EXPRESSION)
        annotation class Listening(val timeout: Long = 0L, val channel: Short = 0)

        @Retention(SOURCE)
        @Target(FUNCTION, EXPRESSION)
        annotation class Remitting(
            val delay: Long = 0L,
            val timeout: Long = -1L,
            val channel: Short = 0,
            val pathwise: SchedulerPath = [])

        @Retention(SOURCE)
        @Target(FUNCTION, EXPRESSION)
        annotation class Repeating(
            val count: Int = 0,
            val delay: Long = 0L,
            val timeout: Long = -1L,
            val channel: Short = 0,
            val pathwise: SchedulerPath = [])
    }
    private val KCallable<*>.event
        get() = annotations.find { it is Event } as? Event
    fun annotatedEventOf(step: KFunction<*>) =
        step.event!!
    fun trySafelyForAnnotatedEventOf(step: KFunction<*>) =
        trySafelyForResult { annotatedEventOf(step) }

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R  {
        // context expansion by attachment: register operation callback.
        // return a default state or a new one depending on the initial value.
        return operation.invoke(initial, _element)
    }
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        // context element lookup
        return null
    }
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        // context convergence by detachment: unregister element and restate.
        return this
    }

    override fun onChanged(step: Step?) {
        if (step !== null) runBlocking { step() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    object EventBus : AbstractFlow<Step?>() {
        override suspend fun collectSafely(collector: FlowCollector<Step?>) {
            // emit signalled events to collector
        }

        fun event(step: ContextStep) {
            // record context event
        }
        fun signal(transit: Short?) {
            // record signal event
        }

        open class Relay(val transit: Short? = null) : Step {
            override suspend fun invoke() {}
        }
    }

    enum class Lock : State { Closed, Open }

    override fun invoke(work: SchedulerWork) = this.work()
}

fun Step.relay(transit: Short? = this.transit) = Relay(transit)
fun Step.reevaluate(transit: Short? = this.transit) = object : Relay(transit) {
    override suspend fun invoke() = this@reevaluate.invoke()
}

val Step.transit
    get() = if (this is Relay) transit else annotatedEvent?.transit
val ContextStep.transit
    get() = annotatedEvent?.transit
private val Any.annotatedEvent
    get() = Scheduler.trySafelyForAnnotatedEventOf(asFunction())

fun scheduleNow(step: Step) { Scheduler.value = step }
fun schedule(step: Step) = Scheduler.postValue(step)
fun Context.scheduleNow(ref: ContextStep) = scheduleNow(step = { ref() })
fun Context.schedule(ref: ContextStep) = schedule(step = { ref() })

fun service(step: CoroutineStep) = runBlocking {
    step(
        Scheduler.trySafelyForAnnotatedScopeOf(step) ?:
        service!!)
}
fun clock(callback: Runnable) = Scheduler.clock!!.post(callback)
fun clockAhead(callback: Runnable) = Scheduler.clock!!.postAhead(callback)
fun <T> clock(step: suspend CoroutineScope.() -> T) = clock(blockingRunnableOf(step))
fun <T> clockAhead(step: suspend CoroutineScope.() -> T) = clockAhead(blockingRunnableOf(step))
fun <T> clockSafely(step: suspend CoroutineScope.() -> T) = clock(safeRunnableOf(step))
fun <T> clockAheadSafely(step: suspend CoroutineScope.() -> T) = clockAhead(safeRunnableOf(step))
fun <T> clockInterrupting(step: suspend CoroutineScope.() -> T) = clock(interruptingRunnableOf(step))
fun <T> clockAheadInterrupting(step: suspend CoroutineScope.() -> T) = clockAhead(interruptingRunnableOf(step))
fun <T> clockSafelyInterrupting(step: suspend CoroutineScope.() -> T) = clock(safeInterruptingRunnableOf(step))
fun <T> clockAheadSafelyInterrupting(step: suspend CoroutineScope.() -> T) = clockAhead(safeInterruptingRunnableOf(step))
fun <T> blockingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { runBlocking(block = step) }
fun <T> safeRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafely { runBlocking(block = step) } }
fun <T> interruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { tryInterrupting(step) { runBlocking(block = step) } }
fun <T> safeInterruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafelyInterrupting(step) { runBlocking(block = step) } }
inline fun <R> commitAsync(lock: Any, crossinline predicate: Predicate, crossinline block: () -> R) {
    if (predicate())
        synchronized(lock) {
            if (predicate()) block()
        }
}
inline fun <R> commitAsyncForResult(lock: Any, crossinline predicate: Predicate, fallback: R? = null, crossinline block: () -> R): R? {
    if (predicate())
        synchronized(lock) {
            if (predicate()) return block()
        }
    return fallback
}

inline fun <R> sequencer(block: Sequencer.() -> R) = Scheduler.sequencer!!.block()
fun <T, R> capture(context: CoroutineContext, step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    liveData(context, block = step) to capture
fun <T, R> ioCapture(step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    capture(IO, step, capture)
fun <T, R> mainCapture(step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    capture(Main, step, capture)
fun <T, R> defaultCapture(step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    capture(Default, step, capture)
fun <T, R> unconfinedCapture(step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    capture(Unconfined, step, capture)
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observer: Observer<T> = disposerOf(this)): Observer<T> {
    first.observe(owner, observer)
    return observer
}
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: Observer<T> = disposerOf(this)): Observer<T> {
    first.observeForever(observer)
    return observer
}
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observerOf: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(owner, observerOf(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observerOf: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(observerOf(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObserver(observer: Observer<T>) =
    first.removeObserver(observer)
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObservers(owner: LifecycleOwner) =
    first.removeObservers(owner)
fun <T, R> captureOf(liveStep: Pair<LiveData<T>, (T) -> R>) = Observer<T> { liveStep.second(it) }
private fun <T, R> disposerOf(liveStep: Pair<LiveData<T>, (T) -> R>) = object : Observer<T> {
    override fun onChanged(value: T) {
        val (step, capture) = liveStep
        step.removeObserver(this)
        capture(value)
    }
}

private fun CoroutineContext.isSchedulerContext() =
    this is Scheduler || this[_key] is SchedulerKey
private fun workerGroupOf(context: CoroutineContext) =
    if (context.isSchedulerContext()) context
    else Scheduler + context
private fun LifecycleOwner.trySafelyForAnnotatedScopeOf(step: CoroutineStep) =
    Scheduler.trySafelyForAnnotatedScopeOf(step) ?:
    lifecycleScope
fun LifecycleOwner.launch(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    trySafelyForAnnotatedScopeOf(step).launch(workerGroupOf(context), start, step)
fun LifecycleOwner.launch(context: CoroutineContext, step: CoroutineStep) =
    trySafelyForAnnotatedScopeOf(step).launch(workerGroupOf(context), block = step)
fun LifecycleOwner.launch(start: CoroutineStart, step: CoroutineStep) =
    trySafelyForAnnotatedScopeOf(step).launch(Scheduler, start, step)
fun LifecycleOwner.launch(step: CoroutineStep) =
    trySafelyForAnnotatedScopeOf(step).launch(Scheduler, block = step)
fun LifecycleOwner.relaunchJobIfNotActive(
    instance: KMutableProperty<Job?>,
    context: CoroutineContext = Scheduler,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: CoroutineStep) =
    instance.mark(
        if (instance.getter.call()?.isActive == true)
            instance as Job
        else launch(context, start, block))
fun CoroutineScope.relaunchJobIfNotActive(
    instance: KMutableProperty<Job?>,
    context: CoroutineContext = Scheduler,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: CoroutineStep) =
    instance.mark(
        if (instance.getter.call()?.isActive == true)
            instance as Job
        else launch(workerGroupOf(context), start, block))
fun LifecycleOwner.close(node: SchedulerNode) {}
fun LifecycleOwner.detach(node: SchedulerNode) {}
fun LifecycleOwner.reattach(node: SchedulerNode) {}
fun Job.close(node: SchedulerNode) {}
fun Job.close() {}
val Job.node: SchedulerNode
    get() = TODO()

infix fun Job.then(next: DescriptiveStep): CoroutineStep = {}
infix fun Job.given(predicate: JobPredicate): CoroutineStep = {}
infix fun Job.from(next: DescriptiveStep): CoroutineStep = {}
infix fun Job.onCancel(action: DescriptiveStep): CoroutineStep = {}
infix fun Job.onError(action: DescriptiveStep): CoroutineStep =  {}
infix fun Job.onTimeout(action: DescriptiveStep): CoroutineStep =  {}
infix fun CoroutineStep.then(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.given(predicate: JobPredicate): CoroutineStep = {}
infix fun CoroutineStep.otherwise(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.from(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onCancel(action: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onError(action: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onTimeout(action: DescriptiveStep): CoroutineStep = {}

fun SchedulerScope.keepAlive(node: SchedulerNode): Boolean = false
fun SchedulerScope.keepAliveOrClose(node: SchedulerNode, job: Job) {
    keepAlive(node) && return
    job.close(node)
}
fun SchedulerScope.keepAliveOrClose(job: Job) {
    job.node.let {
        keepAlive(it) && return
        job.close(it)
    }
}
fun SchedulerScope.close(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.enact(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.error(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.retry(job: Job, exit: ThrowableFunction? = null) {}

private var jobs: JobFunctionSet? = null
private fun JobFunctionSet.save(tag: String, keep: Boolean, function: KCallable<*>) {}
private fun JobFunctionSet.save(tag: String, function: KCallable<*>) =
    save(tag, trySafelyForAnnotatedTagOf(function)?.keep ?: true, function)
private fun JobFunctionSet.save(tag: Tag?, function: KCallable<*>) {}
operator fun Job.set(tag: String, value: Any) {
    jobs?.save(tag, value.asFunction())
}
fun KCallable<*>.markTag() {
    jobs?.save(trySafelyForAnnotatedTagOf(this), this)
}
fun CoroutineScope.markFunctionTags(vararg function: Any?) {
    function.forEach {
        if (it is KCallable<*>)
            it.markTag()
    }
}
fun KMutableProperty<Job?>.mark(job: Job): KMutableProperty<Job?> {
    setter.call(job)
    markTag()
    return this
}

private typealias JobFunctionSet = MutableSet<Pair<String, Job>>
typealias JobFunction = suspend (Any?) -> Unit
fun Any.markTagAsFunction() = asFunction().markTag()

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Tag(val string: String, val keep: Boolean = true)
private val KCallable<*>.tag
    get() = annotations.find { it is Tag } as? Tag
fun annotatedTagOf(item: KCallable<*>) =
    item.tag
fun trySafelyForAnnotatedTagOf(item: KCallable<*>) =
    trySafelyForResult { annotatedTagOf(item) }

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTreeRoot

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTree(val branch: String = "", val level: UByte = 0u)

infix fun <R, S> (suspend () -> R).then(next: suspend () -> S): suspend () -> S = {
    this@then()
    next()
}
infix fun <R, S> (suspend () -> R).thru(next: suspend (R) -> S): suspend () -> S = {
    next(this@thru())
}
fun <R> (suspend () -> R).given(predicate: Predicate, fallback: R): suspend () -> R = {
    if (predicate()) this@given() else fallback
}
infix fun Step.given(predicate: Predicate): Step = given(predicate, Unit)
infix fun <T, R, S> (suspend T.() -> R).then(next: suspend T.() -> S): suspend T.() -> S = {
    this@then()
    next()
}
infix fun <T, R, S> (suspend T.() -> R).thru(next: suspend (R) -> S): suspend T.() -> S = {
    next(this@thru())
}
fun <T, R> (suspend T.() -> R).given(predicate: Predicate, fallback: R): suspend T.() -> R = {
    if (predicate()) this@given() else fallback
}

infix fun <R, S> (() -> R).then(next: () -> S): () -> S = {
    this@then()
    next()
}
infix fun <R, S> (() -> R).thru(next: (R) -> S): () -> S = {
    next(this@thru())
}
fun <R> (() -> R).given(predicate: Predicate, fallback: R): () -> R = {
    if (predicate()) this@given() else fallback
}
infix fun AnyFunction.given(predicate: Predicate): AnyFunction = given(predicate, Unit)

infix fun <T, R, S> ((T) -> R).thru(next: (R) -> S): (T) -> S = {
    next(this@thru(it))
}

fun <R> KCallable<R>.with(vararg args: Any?): () -> R = {
    this@with.call(args)
}
fun <R> with(vararg args: Any?): (KCallable<R>) -> R = {
    it.call(args)
}

val currentThread
    get() = Thread.currentThread()
val mainThread = currentThread
fun Thread.isMainThread() = this === mainThread
fun onMainThread() = currentThread.isMainThread()
private fun withPriority(priority: Int, target: Runnable) = Thread(target).also { it.priority = priority }

abstract class Deferral {
    abstract fun commit(): Unit?
}
abstract class WorkRef : Deferral() {
    var work: Work? = null
    var id: AnyArray? = null
    override fun commit(): Unit? {
        work?.invoke() ?: return null
        return Unit
    }
}
abstract class StepRef : WorkRef() {
    var step: CoroutineStep? = null
}
interface Resolver : SchedulerScope {
    fun resolve(vararg id: Any?): Unit =
        throw BaseImplementationRestriction
}
private fun Resolver.assignWork(work: Work, vararg id: Any?) {
    (this as WorkRef).work = work
    this.id = id
}
private fun Resolver.assignStep(step: CoroutineStep?) {
    (this as StepRef).step = step
}
private fun Resolver.assignStepThenResolve(step: CoroutineStep?) {
    assignStep(step)
    (this as StepResolver).resolve()
}
abstract class WorkResolver : WorkRef(), Resolver
abstract class ForgetfulWorkResolver : WorkRef(), Resolver {
    override fun commit() {
        super.commit()
        work = null
        id = null
    }
}
abstract class StepResolver : StepRef(), Resolver
abstract class ForgetfulStepResolver : StepRef(), Resolver {
    override fun resolve(vararg id: Any?) {
        commit()
        work = null
        this.id = null
        step = null
    }
}

private typealias JobPredicate = (Job) -> Boolean
private typealias SchedulerNode = KClass<out Annotation>
private typealias SchedulerPath = Array<KClass<out Throwable>>
private typealias SchedulerWork = Scheduler.() -> Unit
private typealias DescriptiveStep = suspend SchedulerScope.(Job) -> Unit
private typealias SequencerWork = Sequencer.() -> Unit
private typealias SequencerScope = LiveDataScope<Step?>
suspend fun SequencerScope.change(stage: ContextStep) { emit { EventBus.event(stage) } }
suspend fun SequencerScope.change(transit: Short) { emit { EventBus.signal(transit) } }
suspend fun SequencerScope.reset() { emit { Scheduler.sequencer!!.reset() } }
private typealias SequencerStep = suspend SequencerScope.() -> Unit
private typealias StepObserver = Observer<Step?>
private typealias LiveStep = LiveData<Step?>
private typealias LiveStepFunction = () -> LiveStep?
private typealias CaptureFunction = AnyFunction
private typealias LiveWork = Triple<LiveStepFunction, CaptureFunction?, Boolean>
private typealias LiveSequence = MutableList<LiveWork>
private typealias LiveWorkPredicate = (LiveWork) -> Boolean

private typealias RunnableList = MutableList<Runnable>
private typealias MessageFunction = (Message) -> Any?
typealias Work = () -> Unit
typealias Step = suspend () -> Unit
typealias CoroutineStep = suspend CoroutineScope.() -> Unit

interface Expiry : MutableSet<Lifetime> {
    fun unsetAll(property: KMutableProperty<*>) {
        // must be strengthened by connecting to other expiry sets
        forEach { alive ->
            if (alive(property) == false)
                property.expire()
        }
    }
    companion object : Expiry {
        override fun add(element: Lifetime) = false
        override fun addAll(elements: Collection<Lifetime>) = false
        override fun clear() {}
        override fun iterator(): MutableIterator<Lifetime> = TODO()
        override fun remove(element: Lifetime): Boolean = false
        override fun removeAll(elements: Collection<Lifetime>) = false
        override fun retainAll(elements: Collection<Lifetime>) = false
        override val size: Int
            get() = 0
        override fun contains(element: Lifetime) = false
        override fun containsAll(elements: Collection<Lifetime>) = false
        override fun isEmpty() = true
    }
}
typealias Lifetime = (KMutableProperty<*>) -> Boolean?
private fun KMutableProperty<*>.expire() = setter.call(null)

fun Any.asCallable() = this as KCallable<*>
fun Any.asFunction() = this as KFunction<*>
fun Any.asProperty() = this as KProperty<*>
fun Any.asMutableProperty() = this as KMutableProperty<*>
private typealias ResolverKClass = KClass<out Deferral>
private typealias ResolverKProperty = KMutableProperty<out Deferral?>

private typealias ID = Short
sealed interface State {
    object Failed : Resolved
    object Succeeded : Resolved
    object Pending : Resolved, Ambiguous
    interface Resolved : State {
        companion object : Resolved
    }
    interface Unresolved : State {
        companion object : Unresolved
    }
    object Suspending : Ambiguous
    interface Ambiguous : State {
        companion object : Ambiguous
    }
    companion object {
        operator fun invoke(): State = Lock.Open
        operator fun get(id: ID): State = Lock.Open
        operator fun set(id: ID, lock: Any) {
            when (id.toInt()) {
                1 -> if (lock is Resolved) Scheduler.windDownClock()
                2 -> if (lock is Resolved) Scheduler.serviceOnStartCommandResolver = null
            }
        }
        operator fun plus(lock: Any): State = Ambiguous
        operator fun plusAssign(lock: Any) {}
        operator fun minus(lock: Any): State = Ambiguous
        operator fun minusAssign(lock: Any) {}
        operator fun times(lock: Any): State = Ambiguous
        operator fun timesAssign(lock: Any) {}
        operator fun div(lock: Any): State = Ambiguous
        operator fun divAssign(lock: Any) {}
        operator fun rem(lock: Any): State = Ambiguous
        operator fun remAssign(lock: Any) {}
        operator fun unaryPlus(): State = Ambiguous
        operator fun unaryMinus(): State = Ambiguous
        operator fun rangeTo(lock: Any): State = Ambiguous
        operator fun not(): State = Ambiguous
        operator fun contains(lock: Any) = false
        operator fun compareTo(lock: Any) = 1
    }
    operator fun invoke(): Lock = this as Lock
    operator fun inc() = this
    operator fun dec() = this
    operator fun get(id: ID) = this
    operator fun set(id: ID, state: Any) {}
    operator fun plus(state: Any): State {
        when {
            this === State[2] && state is Pending ->
                if (service?.hasNoMoreInitWork == true)
                    State[2] = Succeeded
        }
        return this
    }
    operator fun minus(state: Any) = this
    operator fun times(state: Any) = this
    operator fun div(state: Any) = this
    operator fun rem(state: Any) = this
    operator fun unaryPlus() = this
    operator fun unaryMinus() = this
    operator fun rangeTo(state: Any) = this
    operator fun not(): State = this
    operator fun contains(state: Any) = state === this
    operator fun compareTo(state: Any) = 0
}