package net.consolator

import android.content.*
import android.os.*
import android.os.Process
import androidx.lifecycle.*
import androidx.room.*
import java.io.*
import java.lang.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.consolator.Scheduler.EventBus
import net.consolator.Scheduler.EventBus.Relay
import net.consolator.Scheduler.Lock
import net.consolator.Scheduler.Sequencer
import net.consolator.application.*
import net.consolator.BaseActivity.*
import android.app.Service.START_NOT_STICKY
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.Unconfined
import net.consolator.Scheduler.clock
import net.consolator.Scheduler.sequencer

sealed interface SchedulerScope : CoroutineScope {
    override val coroutineContext
        get() = Scheduler
    fun commit(step: CoroutineStep): Boolean
}

private interface SchedulerKey : CoroutineContext.Key<SchedulerElement>
private interface SchedulerElement : CoroutineContext.Element
private lateinit var _key: SchedulerKey
private lateinit var _element: SchedulerElement

object Scheduler : MutableLiveData<Step?>(), SchedulerScope, CoroutineContext, StepObserver, (SchedulerWork) -> Unit {
    fun <T : Resolver> defer(resolver: KClass<out T>, provider: Any = resolver, vararg context: Any?): Unit? {
        fun ResolverKProperty.setResolverThenCommit() =
            reconstruct(provider).getter.call()?.commit(context)
        return when (resolver) {
            Migration::class ->
                ::applicationMigrationResolver.setResolverThenCommit()
            ConfigurationChangeManager::class ->
                ::activityConfigurationChangeManager.setResolverThenCommit()
            NightModeChangeManager::class ->
                ::activityNightModeChangeManager.setResolverThenCommit()
            LocalesChangeManager::class ->
                ::activityLocalesChangeManager.setResolverThenCommit()
            MemoryManager::class ->
                null
            else -> null
        }
    }

    private var activityConfigurationChangeManager: ConfigurationChangeManager? = null
    private var activityNightModeChangeManager: NightModeChangeManager? = null
    private var activityLocalesChangeManager: LocalesChangeManager? = null
    var applicationMigrationResolver: Migration? = null

    sealed interface BaseServiceScope : IBinder, (Intent?) -> IBinder, SchedulerScope, SystemContext, UniqueContext {
        fun getStartTimeExtra(intent: Intent?) =
            intent?.getLongExtra(START_TIME_KEY, foregroundContext.asType<UniqueContext>()!!.startTime)!!

        var mode: Int?
        fun getModeExtra(intent: Intent?) =
            intent?.getIntExtra(MODE_KEY, mode ?: START_NOT_STICKY)!!

        val hasMoreInitWork
            get() = logDb === null || netDb === null

        override fun invoke(intent: Intent?): IBinder {
            mode = getModeExtra(intent)
            if (hasMoreInitWork) commit @Tag("init") {
                startTime = getStartTimeExtra(intent)
                Sequencer {
                    if (logDb === null) with(LogDatabase) {
                        io(true,
                            @Tag(STAGE_BUILD)
                            seqStepBuildDatabase(
                                ::logDb,
                                stage = Context::stageLogDbCreated)) }
                    if (netDb === null) with(NetworkDatabase) {
                        io(true,
                            @Tag(STAGE_BUILD)
                            seqStepBuildDatabase(
                                ::netDb,
                                step = { /* update net db records */ },
                                stage = Context::stageNetDbInitialized)) }
                    resume()
                }
                info(SVC_TAG, "Clock is detected.")
            }
            return this
        }

        private fun <D : RoomDatabase> seqStepBuildDatabase(
            instance: KMutableProperty<out D?>,
            tag: StringFunction = ::returnItsTag,
            stage: ContextStep? = null
        ): SequencerStep = object : SequencerStep {
            override suspend fun invoke(scope: SequencerScope) {
                scope.commitStageBuildDatabase(instance, tag(this), stage) } }
        private fun <D : RoomDatabase> seqStepBuildDatabase(
            instance: KMutableProperty<out D?>,
            tag: StringFunction = ::returnItsTag,
            step: Step,
            stage: ContextStep? = null
        ): SequencerStep = object : SequencerStep {
            override suspend fun invoke(scope: SequencerScope) {
                scope.commitStageBuildDatabase(instance, tag(this), step, stage) } }

        private suspend fun <D : RoomDatabase> SequencerScope.commitStageBuildDatabase(instance: KMutableProperty<out D?>, tag: String, stage: ContextStep?, action: Step = { change(stage!!) }) =
            commitAsyncOrResetByTag(instance, tag, {
                buildDatabaseOrResetByTag(instance, tag) },
                post = action)
        private suspend fun <D : RoomDatabase> SequencerScope.commitStageBuildDatabase(instance: KMutableProperty<out D?>, tag: String, step: Step, stage: ContextStep?, action: Step = { step(); change(stage!!) }) =
            commitAsyncOrResetByTag(instance, tag, {
                buildDatabaseOrResetByTag(instance, tag) },
                post = action)
        private suspend fun <D : RoomDatabase> SequencerScope.buildDatabaseOrResetByTag(instance: KMutableProperty<out D?>, tag: String) =
            instance.setter.call(ref?.get()?.run {
                sequencer { resetByTagOnError(tag, ::buildDatabase) } })

        private fun SequencerScope.commitAsyncOrResetByTag(lock: KProperty<*>, tag: String, block: Step, condition: PropertyCondition = ::whenNotNull, post: Step = emptyStep) =
            commitAsyncOrResetByTag(lock, tag) {
                block()
                condition(lock, post) }
        private fun SequencerScope.commitAsyncOrResetByTag(lock: KProperty<*>, tag: String, block: Step) =
            commitAsyncBlocking(lock, block) { resetByTag(tag) }

        override fun commit(step: CoroutineStep) = clockAhead(step::invoke)

        fun clearObjects() {
            mode = null
        }

        val SVC_TAG
            get() = if (onMainThread()) "SERVICE" else "CLOCK"

        override fun getInterfaceDescriptor(): String? {
            return null
        }

        override fun pingBinder(): Boolean {
            return true
        }

        override fun isBinderAlive(): Boolean {
            return false
        }

        override fun queryLocalInterface(descriptor: String): IInterface? {
            return null
        }

        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}

        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return true
        }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            return true
        }
    }

    open class Clock(
        name: String,
        priority: Int = currentThread.priority
    ) : HandlerThread(name, priority) {
        var handler: Handler? = null
        private var queue: RunnableList

        init {
            queue = java.util.LinkedList()
        }
        constructor() : this("clk")
        constructor(callback: Runnable) : this() {
            queue.add(callback)
        }

        var id: Int = -1
        override fun start() {
            commitAsync(this, { !isAlive }) {
                super.start()
                id = synchronized(Clock::class) { count++ }
            }
        }
        fun alsoStart(): Clock {
            start()
            return this
        }

        override fun run() {
            hLock = Lock.Open()
            handler = object : Handler(looper) {
                override fun handleMessage(msg: Message) {
                    super.handleMessage(msg)
                    turn(msg)
                }
            }
            queue.run()
        }
        private fun turn(msg: Message) {
            if (isSynchronized(msg))
                commit {
                    queue.run(msg) || return@commit
                    msg.callback.run()
                }
            else msg.callback.run()
        }
        private fun isSynchronized(msg: Message): Boolean {
            return true
        }
        private fun RunnableList.run(msg: Message? = null): Boolean {
            onEach {
                synchronized(sLock) {
                    it.run()
                    remove(it)
                }
            }
            return true
        }

        private lateinit var hLock: Lock
        fun <R> commit(block: () -> R) = synchronized(hLock(block)) {
            hLock = Lock.Closed(block)
            block().also {
                hLock = Lock.Open(block, it)
            }
        }

        private var sLock = Any()
        var post = fun(callback: Runnable) =
            handler?.post(callback) ?:
            synchronized(sLock) { queue.add(callback) }
        var postAhead = fun(callback: Runnable) =
            handler?.postAtFrontOfQueue(callback) ?:
            synchronized(sLock) { queue.add(0, callback); true }

        fun clearObjects() {
            handler = null
            queue.clear()
        }
        companion object {
            var count = 0
                private set
        }
    }

    class Sequencer {
        fun io(async: Boolean = false, step: SequencerStep) = attach(IO, async, step)
        fun io(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, IO, async, step)
        fun ioStart(step: SequencerStep) = io(false, step).also { start() }
        fun ioResume(async: Boolean = false, step: SequencerStep) = io(async, step).also { resume() }
        fun ioAfter(async: Boolean = false, step: SequencerStep) = attachAfter(IO, async, step)
        fun ioBefore(async: Boolean = false, step: SequencerStep) = attachBefore(IO, async, step)
        fun ioResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = io(async, resettingByTagFirstly(step))
        fun ioResettingByTagLastly(async: Boolean = false, step: SequencerStep) = io(async, resettingByTagLastly(step))
        fun ioResettingByTagFirstly(index: Int, async: Boolean = false, step: SequencerStep) = io(index, async, resettingByTagFirstly(step))
        fun ioResettingByTagLastly(index: Int, async: Boolean = false, step: SequencerStep) = io(index, async, resettingByTagLastly(step))
        fun ioStartResettingByTagFirstly(step: SequencerStep) = ioResettingByTagFirstly(false, step).also { start() }
        fun ioStartResettingByTagLastly(step: SequencerStep) = ioResettingByTagLastly(false, step).also { start() }
        fun ioResumeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = ioResettingByTagFirstly(async, step).also { resume() }
        fun ioResumeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = ioResettingByTagLastly(async, step).also { resume() }
        fun ioAfterResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = ioAfter(async, resettingByTagFirstly(step))
        fun ioAfterResettingByTagLastly(async: Boolean = false, step: SequencerStep) = ioAfter(async, resettingByTagLastly(step))
        fun ioBeforeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = ioBefore(async, resettingByTagFirstly(step))
        fun ioBeforeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = ioBefore(async, resettingByTagLastly(step))

        fun main(async: Boolean = false, step: SequencerStep) = attach(Main, async, step)
        fun main(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Main, async, step)
        fun mainStart(step: SequencerStep) = main(false, step).also { start() }
        fun mainResume(async: Boolean = false, step: SequencerStep) = main(async, step).also { resume() }
        fun mainAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Main, async, step)
        fun mainBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Main, async, step)
        fun mainResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = main(async, resettingByTagFirstly(step))
        fun mainResettingByTagLastly(async: Boolean = false, step: SequencerStep) = main(async, resettingByTagLastly(step))
        fun mainResettingByTagFirstly(index: Int, async: Boolean = false, step: SequencerStep) = main(index, async, resettingByTagFirstly(step))
        fun mainResettingByTagLastly(index: Int, async: Boolean = false, step: SequencerStep) = main(index, async, resettingByTagLastly(step))
        fun mainStartResettingByTagFirstly(step: SequencerStep) = mainResettingByTagFirstly(false, step).also { start() }
        fun mainStartResettingByTagLastly(step: SequencerStep) = mainResettingByTagLastly(false, step).also { start() }
        fun mainResumeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = mainResettingByTagFirstly(async, step).also { resume() }
        fun mainResumeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = mainResettingByTagLastly(async, step).also { resume() }
        fun mainAfterResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = mainAfter(async, resettingByTagFirstly(step))
        fun mainAfterResettingByTagLastly(async: Boolean = false, step: SequencerStep) = mainAfter(async, resettingByTagLastly(step))
        fun mainBeforeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = mainBefore(async, resettingByTagFirstly(step))
        fun mainBeforeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = mainBefore(async, resettingByTagLastly(step))

        fun default(async: Boolean = false, step: SequencerStep) = attach(Default, async, step)
        fun default(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Default, async, step)
        fun defaultStart(step: SequencerStep) = default(false, step).also { start() }
        fun defaultResume(async: Boolean = false, step: SequencerStep) = default(async, step).also { resume() }
        fun defaultAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Default, async, step)
        fun defaultBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Default, async, step)
        fun defaultResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = default(async, resettingByTagFirstly(step))
        fun defaultResettingByTagLastly(async: Boolean = false, step: SequencerStep) = default(async, resettingByTagLastly(step))
        fun defaultResettingByTagFirstly(index: Int, async: Boolean = false, step: SequencerStep) = default(index, async, resettingByTagFirstly(step))
        fun defaultResettingByTagLastly(index: Int, async: Boolean = false, step: SequencerStep) = default(index, async, resettingByTagLastly(step))
        fun defaultStartResettingByTagFirstly(step: SequencerStep) = defaultResettingByTagFirstly(false, step).also { start() }
        fun defaultStartResettingByTagLastly(step: SequencerStep) = defaultResettingByTagLastly(false, step).also { start() }
        fun defaultResumeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = defaultResettingByTagFirstly(async, step).also { resume() }
        fun defaultResumeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = defaultResettingByTagLastly(async, step).also { resume() }
        fun defaultAfterResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = defaultAfter(async, resettingByTagFirstly(step))
        fun defaultAfterResettingByTagLastly(async: Boolean = false, step: SequencerStep) = defaultAfter(async, resettingByTagLastly(step))
        fun defaultBeforeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = defaultBefore(async, resettingByTagFirstly(step))
        fun defaultBeforeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = defaultBefore(async, resettingByTagLastly(step))

        fun unconfined(async: Boolean = false, step: SequencerStep) = attach(Unconfined, async, step)
        fun unconfined(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Unconfined, async, step)
        fun unconfinedStart(step: SequencerStep) = unconfined(false, step).also { start() }
        fun unconfinedResume(async: Boolean = false, step: SequencerStep) = unconfined(async, step).also { resume() }
        fun unconfinedAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Unconfined, async, step)
        fun unconfinedBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Unconfined, async, step)
        fun unconfinedResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingByTagFirstly(step))
        fun unconfinedResettingByTagLastly(async: Boolean = false, step: SequencerStep) = unconfined(async, resettingByTagLastly(step))
        fun unconfinedResettingByTagFirstly(index: Int, async: Boolean = false, step: SequencerStep) = unconfined(index, async, resettingByTagFirstly(step))
        fun unconfinedResettingByTagLastly(index: Int, async: Boolean = false, step: SequencerStep) = unconfined(index, async, resettingByTagLastly(step))
        fun unconfinedStartResettingByTagFirstly(step: SequencerStep) = unconfinedResettingByTagFirstly(false, step).also { start() }
        fun unconfinedStartResettingByTagLastly(step: SequencerStep) = unconfinedResettingByTagLastly(false, step).also { start() }
        fun unconfinedResumeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = unconfinedResettingByTagFirstly(async, step).also { resume() }
        fun unconfinedResumeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = unconfinedResettingByTagLastly(async, step).also { resume() }
        fun unconfinedAfterResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = unconfinedAfter(async, resettingByTagFirstly(step))
        fun unconfinedAfterResettingByTagLastly(async: Boolean = false, step: SequencerStep) = unconfinedAfter(async, resettingByTagLastly(step))
        fun unconfinedBeforeResettingByTagFirstly(async: Boolean = false, step: SequencerStep) = unconfinedBefore(async, resettingByTagFirstly(step))
        fun unconfinedBeforeResettingByTagLastly(async: Boolean = false, step: SequencerStep) = unconfinedBefore(async, resettingByTagLastly(step))

        private constructor(observer: StepObserver) { this.observer = observer }
        constructor() : this(Scheduler)

        private val observer: StepObserver
        private var seq: LiveSequence = mutableListOf()
        private var ln = -1
        private val work
            get() = seq[ln]
        private var latestStep: LiveStep? = null
        private var latestCapture: Any? = null

        fun <R> commit(block: () -> R) = synchronized(mLock, block)
        private val mLock: Any
            get() = seq // lock resolution may interrupt thread here

        private fun init() {
            ln = -1
            clearFlags()
            clearLatestObjects()
        }
        fun start() {
            init()
            resume()
        }
        fun resume(index: Int) {
            ln = index
            resume()
        }
        fun resume(tag: String) {
            fun indexOf(tag: String): Int = TODO()
            resume(indexOf(tag))
        }
        fun resume() {
            isActive = true
            advance()
        }
        fun retry() {
            ln -= 1
            resume()
        }
        var activate = fun() = Unit
        private fun prepare() {
            if (ln < -1) ln = -1
        }
        fun jump(index: Int) =
            if (hasError) null
            else (index < seq.size && (!isObserving || seq[index].third)).also {
                if (it) ln = index
            }
        var next = fun(index: Int) = jump(index)
        private fun advance() {
            activate()
            prepare()
            while (next(ln + 1) ?: return)
                work.let { run(it) ?: bypass(it) } || return
            isCompleted = finish()
        }
        fun observe(work: LiveWork): Boolean? {
            val (step, _, async) = work
            try {
                step().let { step ->
                    latestStep = step
                    step?.observeForever(observer) ?:
                    return null
                }
            } catch (ex: Throwable) {
                error(ex)
                return false
            }
            isObserving = true
            return async
        }
        var run = fun(work: LiveWork) = observe(work)
        fun capture(work: LiveWork): Boolean {
            work.second.let { capture ->
                latestCapture = capture
                capture?.invoke(work.asCallable())?.let { async ->
                    if (async is Boolean) return async
                }
            }
            return false
        }
        var bypass = fun(work: LiveWork) = capture(work)
        fun end() = !(ln < seq.size || isObserving)
        var finish = fun() = end()

        fun reset(step: LiveStep? = latestStep) {
            step?.removeObserver(observer)
            isObserving = false
        }
        fun resetByTag(tag: String) {}
        fun cancel(ex: Throwable) {
            isCancelled = true
            this.ex = ex
        }
        fun error(ex: Throwable) {
            hasError = true
            this.ex = ex
        }
        var exception = fun(ex: Throwable) = when (ex) {
            is CancellationException ->
                cancel(ex)
            else ->
                error(ex)
        }
        var interrupt = fun(ex: Throwable) = ex

        suspend inline fun <R> SequencerScope.resetOnCancel(block: () -> R) =
            try { block() }
            catch (ex: CancellationException) {
                emitReset()
                exception(ex)
                throw interrupt(ex) }
        suspend inline fun <R> SequencerScope.resetOnError(block: () -> R) =
            try { block() }
            catch (ex: Throwable) {
                emitReset()
                exception(ex)
                throw interrupt(ex) }
        suspend inline fun <R> SequencerScope.resetByTagOnCancel(tag: String, block: () -> R) =
            try { block() }
            catch (ex: CancellationException) {
                emitResetByTag(tag)
                exception(ex)
                throw interrupt(ex) }
        suspend inline fun <R> SequencerScope.resetByTagOnError(tag: String, block: () -> R) =
            try { block() }
            catch (ex: Throwable) {
                emitResetByTag(tag)
                exception(ex)
                throw interrupt(ex) }
        private fun resettingFirstly(step: SequencerStep) = SequencerScope::emitReset then step
        private fun resettingLastly(step: SequencerStep) = step then SequencerScope::emitReset
        private fun resettingByTagFirstly(step: SequencerStep) = step after { emitResetByTag(tagOf(step)) }
        private fun resettingByTagLastly(step: SequencerStep) = step then { emitResetByTag(tagOf(step)) }
        private fun tagOf(step: SequencerStep): String = TODO()

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
            clearError()
        }
        fun clearError() {
            hasError = false
            ex = null
        }
        fun clearLatestObjects() {
            latestStep = null
            latestCapture = null
        }
        fun clearObjects() {
            seq.clear()
            clearLatestObjects()
        }
        companion object : (SequencerWork) -> Unit {
            override fun invoke(work: SequencerWork) = sequencer!!.work()
            const val ATTACHED_ALREADY = -1
        }

        private fun LiveSequence.attach(element: LiveWork) =
            add(element)
        private fun LiveSequence.attach(index: Int, element: LiveWork) =
            add(index, element)
        fun attach(work: LiveWork) =
            commit {
                seq.attach(work)
                seq.size - 1 }.also { ln ->
                    markTagsForSeqAttach(work, ln) }
        fun attachOnce(work: LiveWork) =
            if (work.isNotAttached())
                attach(work)
            else ATTACHED_ALREADY
        fun attachOnce(range: IntRange, work: LiveWork) =
            if (work.isNotAttached(range))
                attach(work)
            else ATTACHED_ALREADY
        fun attachOnce(first: Int, last: Int, work: LiveWork) =
            if (work.isNotAttached(first, last))
                attach(work)
            else ATTACHED_ALREADY
        fun attach(index: Int, work: LiveWork) =
            commit { with(seq) {
                if (ln in index..size)
                    ln += 1
                attach(index, work)
                index } }.also { ln ->
                    markTagsForSeqAttach(work, ln) }
        fun attachOnce(index: Int, work: LiveWork) =
            if (work.isNotAttached(index))
                commit {
                    seq.attach(index, work)
                    index }.also { ln ->
                        markTagsForSeqAttach(work, ln) }
            else ATTACHED_ALREADY
        fun attachOnce(range: IntRange, index: Int, work: LiveWork) =
            if (work.isNotAttached(range, index))
                attach(index, work)
            else ATTACHED_ALREADY
        fun attachOnce(first: Int, last: Int, index: Int, work: LiveWork) =
            if (work.isNotAttached(first, last, index))
                attach(index, work)
            else ATTACHED_ALREADY
        fun attachAfter(work: LiveWork) =
            attach(after, work)
        fun attachBefore(work: LiveWork) =
            attach(before, work)
        fun attachOnceAfter(work: LiveWork) =
            attachOnce(after, work)
        fun attachOnceBefore(work: LiveWork) =
            attachOnce(before, work)

        private fun markTagsForLaunch(step: SequencerStep, capture: CaptureFunction? = null, context: CoroutineContext? = null) =
            step.also { markTagsForSeqLaunch(it, capture, context) }

        fun attach(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = markTagsForLaunch(step)) }.also { attach(it) }
        fun attach(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = markTagsForLaunch(step, capture)) }, capture, async).also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = markTagsForLaunch(step, context = context)) }.also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = markTagsForLaunch(step, capture, context)) }, capture, async).also { attach(it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = markTagsForLaunch(step)) }.also { attach(index, it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = markTagsForLaunch(step, capture)) }, capture, async).also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = markTagsForLaunch(step, context = context)) }.also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = markTagsForLaunch(step, capture, context)) }, capture, async).also { attach(index, it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = markTagsForLaunch(step)) }.also { attachAfter(it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = markTagsForLaunch(step, capture)) }, capture, async).also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = markTagsForLaunch(step, context = context)) }.also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = markTagsForLaunch(step, capture, context)) }, capture, async).also { attachAfter(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = markTagsForLaunch(step)) }.also { attachBefore(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = markTagsForLaunch(step, capture)) }, capture, async).also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = markTagsForLaunch(step, context = context)) }.also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = markTagsForLaunch(step, capture, context)) }, capture, async).also { attachBefore(it) }

        fun capture(block: CaptureFunction) =
            attach(nullStepTo(block))
        fun captureOnce(block: CaptureFunction) =
            if (block.isNotAttached())
                capture(block)
            else ATTACHED_ALREADY
        fun captureOnce(range: IntRange, block: CaptureFunction) =
            if (block.isNotAttached(range))
                capture(block)
            else ATTACHED_ALREADY
        fun captureOnce(first: Int, last: Int, block: CaptureFunction) =
            if (block.isNotAttached(first, last))
                capture(block)
            else ATTACHED_ALREADY
        fun capture(index: Int, block: CaptureFunction) =
            attach(index, nullStepTo(block))
        fun captureAfter(block: CaptureFunction) =
            attachAfter(nullStepTo(block))
        fun captureBefore(block: CaptureFunction) =
            attachBefore(nullStepTo(block))
        fun captureOnce(index: Int, block: CaptureFunction) =
            if (block.isNotAttached(index))
                capture(index, block)
            else ATTACHED_ALREADY
        fun captureOnce(range: IntRange, index: Int, block: CaptureFunction) =
            if (block.isNotAttached(range, index))
                capture(block)
            else ATTACHED_ALREADY
        fun captureOnce(first: Int, last: Int, index: Int, block: CaptureFunction) =
            if (block.isNotAttached(first, last, index))
                capture(block)
            else ATTACHED_ALREADY
        fun captureOnceAfter(block: CaptureFunction) =
            captureOnce(after, block)
        fun captureOnceBefore(block: CaptureFunction) =
            captureOnce(before, block)
        private fun nullStepTo(block: CaptureFunction) = Triple(nullStep, block, false)
        private fun stepToNull(async: Boolean = false, step: LiveStepFunction) = Triple(step, nullBlock, async)
        private val nullStep: LiveStepFunction = @Tag("null-step") { null }
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
                    return false }
            return true }
        private fun LiveWork.isNotAttached(first: Int, last: Int): Boolean {
            for (i in first..last)
                if (seq[i].isSameWork(this))
                    return false
            return true }
        private fun LiveWork.isNotAttached(index: Int) =
            none(index) { it.isSameWork(this) }
        private fun LiveWork.isNotAttached(range: IntRange, index: Int) = when {
            range.isEmpty() -> true
            index - range.first <= range.last - index ->
                range.none { seq[it].isSameWork(this) }
            else ->
                range.noneReversed { seq[it].isSameWork(this) } }
        private fun LiveWork.isNotAttached(first: Int, last: Int, index: Int) = when {
            first < last -> true
            index - first <= last - index ->
                seq.none { it.isSameWork(this) }
            else ->
                seq.noneReversed { it.isSameWork(this) } }
        private fun CaptureFunction.isNotAttached() =
            seq.noneReversed { it.isSameCapture(this) }
        private fun CaptureFunction.isNotAttached(range: IntRange): Boolean {
            range.forEach {
                if (seq[it].isSameCapture(this))
                    return false }
            return true }
        private fun CaptureFunction.isNotAttached(first: Int, last: Int): Boolean {
            for (i in first..last)
                if (seq[i].isSameCapture(this))
                    return false
            return true }
        private fun CaptureFunction.isNotAttached(index: Int) =
            none(index) { it.isSameCapture(this) }
        private fun CaptureFunction.isNotAttached(range: IntRange, index: Int) = when {
            range.isEmpty() -> true
            index - range.first <= range.last - index ->
                range.none { seq[it].isSameCapture(this) }
            else ->
                range.noneReversed { seq[it].isSameCapture(this) } }
        private fun CaptureFunction.isNotAttached(first: Int, last: Int, index: Int) = when {
            first < last -> true
            index - first <= last - index ->
                seq.none { it.isSameCapture(this) }
            else ->
                seq.noneReversed { it.isSameCapture(this) } }
        private inline fun none(index: Int, predicate: LiveWorkPredicate) = with(seq) {
            when {
                index < size / 2 ->
                    none(predicate)
                else ->
                    noneReversed(predicate) } }
        private inline fun LiveSequence.noneReversed(predicate: LiveWorkPredicate): Boolean {
            if (size == 0) return true
            for (i in (size - 1) downTo 0)
                if (predicate(this[i]))
                    return false
            return true }
        private inline fun IntRange.noneReversed(predicate: IntPredicate): Boolean {
            reversed().forEach {
                if (predicate(it))
                    return false }
            return true }

        val leading
            get() = 0 until with(seq) { if (ln < size) ln else size }
        val trailing
            get() = (if (ln < 0) 0 else ln + 1) until seq.size

        private val before
            get() = when {
                ln <= 0 -> 0
                ln < seq.size -> ln - 1
                else -> seq.size }
        private val after
            get() = when {
                ln < 0 -> 0
                ln < seq.size -> ln + 1
                else -> seq.size }
    }

    fun observe() = observeForever(this)
    fun observeAsync() = commitAsync(this, { !hasObservers() }, ::observe)
    fun observe(owner: LifecycleOwner) = observe(owner, this)
    fun ignore() = removeObserver(this)
    val observeScheduler = Runnable(::observe)
    val ignoreScheduler = Runnable(::ignore)
    val startSequencer = Runnable { sequencer?.start() }
    val resumeSequencer = Runnable { sequencer?.resume() }
    val retrySequencer = Runnable { sequencer?.retry() }

    var clock: Clock? = null
        get() = field.singleton()
    var sequencer: Sequencer? = null
        get() = field.singleton()

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
        activityConfigurationChangeManager = null
        activityNightModeChangeManager = null
        activityLocalesChangeManager = null
        applicationMigrationResolver = null
    }
    fun clearObjects() {
        clock = null
        sequencer = null
    }

    init {
        _key = object : SchedulerKey {}
        _element = object : SchedulerElement {
            override val key
                get() = _key
        }
    }

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R {
        // context expansion by attachment: register operation callback.
        // return a default state or a new one depending on the initial value.
        return operation(initial, _element)
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

    override fun commit(step: CoroutineStep) = clock(step::invoke)

    @OptIn(ExperimentalCoroutinesApi::class)
    object EventBus : AbstractFlow<Step?>() {
        override suspend fun collectSafely(collector: FlowCollector<Step?>) {
            // emit signalled events to collector
        }

        fun signal(step: ContextStep): Boolean {
            // record context event
            return true
        }
        fun signal(transit: Short?): Boolean {
            // record signal event
            return true
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
    override suspend fun invoke() = this@reevaluate() }

val Step.transit
    get() = if (this is Relay) transit else asCallable().event?.transit

inline fun <reified T : Resolver> LifecycleOwner.defer(member: UnitKFunction, vararg context: Any?) =
    Scheduler.defer(T::class, this, member, *context)
inline fun <reified T : Resolver> LifecycleOwner.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    Scheduler.defer(T::class, this, member, *context, `super`)
inline fun <reified T : Resolver> Context.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    Scheduler.defer(T::class, this, member, *context, `super`)
interface Resolver : SchedulerScope {
    fun commit(vararg context: Any?)
}

fun schedule(step: Step) = Scheduler.postValue(step)
fun scheduleNow(step: Step) { Scheduler.value = step }
fun Context.schedule(ref: ContextStep) = schedule(step = { ref() })
fun Context.scheduleNow(ref: ContextStep) = scheduleNow(step = { ref() })

fun service(task: String) {}
private fun service(step: CoroutineStep) {
    (annotatedScopeOf(step) ?:
    service)?.let { scope ->
        scope::class.memberFunctions.find {
            it.name == "commit" &&
            it.parameters.size == 2 &&
            it.parameters[1].name == "step"
        }?.call(scope, step)
    }
}
fun clock(callback: Runnable) = clock!!.post(callback)
fun clockAhead(callback: Runnable) = clock!!.postAhead(callback)
fun <T> clock(step: suspend CoroutineScope.() -> T) = clock(runnableOf(step))
fun <T> clockAhead(step: suspend CoroutineScope.() -> T) = clockAhead(runnableOf(step))
fun <T> clockSafely(step: suspend CoroutineScope.() -> T) = clock(safeRunnableOf(step))
fun <T> clockAheadSafely(step: suspend CoroutineScope.() -> T) = clockAhead(safeRunnableOf(step))
fun <T> clockInterrupting(step: suspend CoroutineScope.() -> T) = clock(interruptingRunnableOf(step))
fun <T> clockAheadInterrupting(step: suspend CoroutineScope.() -> T) = clockAhead(interruptingRunnableOf(step))
fun <T> clockSafelyInterrupting(step: suspend CoroutineScope.() -> T) = clock(safeInterruptingRunnableOf(step))
fun <T> clockAheadSafelyInterrupting(step: suspend CoroutineScope.() -> T) = clockAhead(safeInterruptingRunnableOf(step))
fun <T> blockOf(step: suspend CoroutineScope.() -> T): () -> T = { runBlocking(block = step) }
fun <T> runnableOf(step: suspend CoroutineScope.() -> T) = Runnable { runBlocking(block = step) }
fun <T> safeRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafely(blockOf(step)) }
fun <T> interruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { tryInterrupting(step) }
fun <T> safeInterruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafelyInterrupting(step) }
inline fun <R> commitAsync(lock: Any, crossinline predicate: Predicate, crossinline block: () -> R) {
    if (predicate())
        synchronized(lock) {
            if (predicate()) block() } }
inline fun <R> commitAsyncForResult(lock: Any, crossinline predicate: Predicate, fallback: R? = null, crossinline block: () -> R): R? {
    if (predicate())
        synchronized(lock) {
            if (predicate()) return block() }
    return fallback }
inline fun <T, R, S> T.commitAsyncBlocking(lock: Any, crossinline predicate: Predicate, crossinline block: suspend T.() -> R, crossinline fallback: suspend T.() -> S) {
    if (predicate())
        synchronized(lock) {
            runBlocking {
                if (predicate()) block()
                else fallback() } }
    else runBlocking { fallback() } }
inline fun <T, R, S : R> T.commitAsyncBlockingForResult(lock: Any, crossinline predicate: Predicate, crossinline block: suspend T.() -> R, crossinline fallback: suspend T.() -> S? = { null }) =
    if (predicate())
        synchronized(lock) {
            runBlocking {
                if (predicate()) block()
                else fallback() } }
    else runBlocking { fallback() }
fun <R, S> commitAsyncBlocking(lock: KProperty<*>, block: suspend () -> R, fallback: suspend () -> S) {
    fun predicate() = lock.getter.call() === null
    if (predicate())
        synchronized(lock) {
            runBlocking {
                if (predicate()) block()
                else fallback() } }
    else runBlocking { fallback() } }

inline fun <R> sequencer(block: Sequencer.() -> R) = sequencer!!.block()
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
    return observer }
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: Observer<T> = disposerOf(this)): Observer<T> {
    first.observeForever(observer)
    return observer }
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observerOf: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(owner, observerOf(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observerOf: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(observerOf(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObserver(observer: Observer<T>) =
    first.removeObserver(observer)
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObservers(owner: LifecycleOwner) =
    first.removeObservers(owner)
fun <T, R> captureOf(liveStep: Pair<LiveData<T>, (T) -> R>) =
    Observer<T> { liveStep.second(it) }
private fun <T, R> disposerOf(liveStep: Pair<LiveData<T>, (T) -> R>) =
    object : Observer<T> {
        override fun onChanged(value: T) {
            val (step, capture) = liveStep
            step.removeObserver(this)
            capture(value) } }

suspend fun SequencerScope.change(stage: ContextStep) = emitResettingByTag(tagOf(stage)) {
    EventBus.signal(stage)
}
suspend fun SequencerScope.change(transit: Short) = emitResetting {
    EventBus.signal(transit)
}
private suspend inline fun <R> SequencerScope.emitResetting(block: () -> R): R {
    emitReset()
    return block() }
private suspend inline fun <R> SequencerScope.emitResettingByTag(tag: String, block: () -> R): R {
    emitResetByTag(tag)
    return block() }
suspend fun SequencerScope.emitReset() = emit { reset() }
suspend fun SequencerScope.emitResetByTag(tag: String) = emit { resetByTag(tag) }
private suspend fun SequencerScope.reset() = sequencer!!.reset()
private suspend fun SequencerScope.resetByTag(tag: String) = sequencer!!.resetByTag(tag)
private fun tagOf(stage: ContextStep): String = TODO()

fun CoroutineScope.relaunch(instance: JobKProperty, context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)
fun LifecycleOwner.relaunch(instance: JobKProperty, context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)
private fun relaunch(launcher: KFunction<Job>, instance: JobKProperty, context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    instance.require({ !it.isActive }) {
        launcher.call(context, start, step)
    }.also { instance.markTag() }

fun launch(context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    ProcessLifecycleOwner.get().launch(context, start, step)
fun LifecycleOwner.launch(context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep): Job {
    val (scope, task) = determineScopeAndCoroutine(context, start, step)
    val (context, start, step) = task
    return scope.launch(context, start, step).also { job ->
        markTagsForJobLaunch(step, job, context, start) } }
private fun LifecycleOwner.determineScopeAndCoroutine(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    determineScope(step).let { scope ->
        scope to scope.determineCoroutine(context, start, step) }
private fun LifecycleOwner.determineScope(step: CoroutineStep) =
    annotatedScopeOf(step) ?: lifecycleScope
private fun CoroutineScope.determineCoroutine(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    Triple(
        if (context.isSchedulerContext()) context
        else Scheduler + context, // buggy! must return background io context by jit reconfiguration
        start,
        step)
private fun CoroutineContext.isSchedulerContext() =
    this is Scheduler || this[_key] is SchedulerKey
fun LifecycleOwner.close(node: SchedulerNode) {}
fun LifecycleOwner.detach(node: SchedulerNode) {}
fun LifecycleOwner.reattach(node: SchedulerNode) {}

infix fun Job.then(next: DescriptiveStep): CoroutineStep = {}
infix fun Job.given(predicate: JobPredicate): CoroutineStep = {}
infix fun Job.from(next: DescriptiveStep): CoroutineStep = {}
infix fun Job.onCancel(action: DescriptiveStep): CoroutineStep = {}
infix fun Job.onError(action: DescriptiveStep): CoroutineStep = {}
infix fun Job.onTimeout(action: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.then(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.given(predicate: JobPredicate): CoroutineStep = {}
infix fun CoroutineStep.otherwise(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.from(next: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onCancel(action: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onError(action: DescriptiveStep): CoroutineStep = {}
infix fun CoroutineStep.onTimeout(action: DescriptiveStep): CoroutineStep = {}

fun SchedulerScope.keepAlive(node: SchedulerNode): Boolean = false
fun SchedulerScope.keepAlive(job: Job) = keepAlive(job.node)
fun SchedulerScope.keepAliveOrClose(node: SchedulerNode, job: Job) {
    keepAlive(node) && return
    job.close(node)
}
fun SchedulerScope.keepAliveOrClose(job: Job) {}
fun SchedulerScope.close(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.enact(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.error(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.retry(job: Job, exit: ThrowableFunction? = null) {}
fun Job.close(node: SchedulerNode) {}
fun Job.close() {}
val Job.node: SchedulerNode
    get() = TODO()

suspend fun CoroutineScope.retrieveContext() =
    currentJob()["context"].asType<Context>()!!
suspend fun CoroutineScope.registerContext(context: WeakContext) {
    currentJob()["context"] = context }

fun SchedulerScope.change(stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(ref: WeakContext, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(ref: WeakContext, owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)

typealias JobFunction = suspend (Any?) -> Unit
private typealias JobFunctionSet = MutableSet<Pair<String, Any>>
private var jobs: JobFunctionSet? = null

operator fun Job.get(tag: String) =
    jobs?.find { tag == it.first }?.second.asType<AnyArray>()?.get(1)
operator fun Job.set(tag: String, value: Any) {
    // addressable layer work
    jobs?.save(tag, value.asCallable()) }

private fun JobFunctionSet.save(tag: String, function: KCallable<*>) = function.tag.let { self ->
    save(combineTags(tag, self?.string), self?.keep ?: true, function) }
private fun JobFunctionSet.save(tag: Tag?, self: KCallable<*>) =
    if (tag !== null) with(tag) { save(string, keep, self) }
    else save(null, false, self)
private fun JobFunctionSet.save(tag: String?, keep: Boolean, function: KCallable<*>) {
    // rewire related parts
    add((tag ?: currentThread.name) to arrayOf(keep, function)) }
private fun combineTags(tag: String, self: String?) =
    if (self === null) tag
    else "$tag.$self"

fun Any.markTag() = asCallable().markTag()
fun KCallable<*>.markTag() = tag.also { jobs?.save(it, this) }
fun markTags(vararg function: Any?) {
    when (function.firstOrNull()) {
        "job.launch" ->
            markTagsForJobLaunch(*function, i = 1)
        "seq.launch" ->
            markTagsForSeqLaunch(*function, i = 1)
        "job.repeat" ->
            markTagsForJobRepeat(*function, i = 1)
        "seq.attach" ->
            markTagsForSeqAttach(*function, i = 1)
        else ->
            function.forEach {
                it.asNullable().markTag() } } }
private fun markTagsForJobLaunch(vararg function: Any?, i: Int = 0) =
    function[i]?.markTag()?.also { step ->
        val stepTag = step.string
        function[i + 1]?.let { job ->
            jobs?.save("${stepTag}.job", step.keep, job.asCallable()) } /* job */
        function[i + 2]?.let { context ->
            jobs?.save("${stepTag}.context", false, context.asCallable()) } /* context */
        function[i + 3]?.let { start ->
            jobs?.save("${stepTag}.start", false, start.asCallable()) } /* start */ }
private fun markTagsForJobRepeat(vararg function: Any?, i: Int = 0) =
    function[i]?.markTag()?.also { block ->
        val blockTag = block.string
        function[i + 1]?.let { delay ->
            jobs?.save("${blockTag}.delay", delay.asCallable()) } /* delay */
        function[i + 2]?.let { predicate ->
            jobs?.save("${blockTag}.predicate", predicate.asCallable()) } /* predicate */ }
private fun markTagsForSeqAttach(vararg function: Any?, i: Int = 0) =
    function[i]?.asType<LiveWork>()?.let { work ->
        work.first.markTag()?.also { stepTag ->
            val stepTag = stepTag.string
            jobs?.save("${stepTag}.work", false, work.asNullable())
            function[i + 1]?.let { ln ->
                jobs?.save("${stepTag}.ln", ln.asNullable()) } } /* work & ln */ }
private fun markTagsForSeqLaunch(vararg function: Any?, i: Int = 0) =
    function[i]?.markTag()?.also { step ->
        val stepTag = step.string
        function[i + 1]?.let { capture ->
            jobs?.save("${stepTag}.capture", capture.asNullable()) } /* capture */
        function[i + 2]?.let { context ->
            jobs?.save("${stepTag}.context", false, context.asNullable()) } /* context */ }
private fun returnItsTag(it: Any?) = it.asNullable().tag!!.string

suspend fun currentJob() = currentCoroutineContext().job

infix fun <R, S> (suspend () -> R).then(next: suspend () -> S): suspend () -> S = {
    this@then()
    next() }
infix fun <R, S> (suspend () -> R).thru(next: suspend (R) -> S): suspend () -> S = {
    next(this@thru()) }
fun <R> (suspend () -> R).given(predicate: Predicate, fallback: R): suspend () -> R = {
    if (predicate()) this@given() else fallback }
infix fun Step.given(predicate: Predicate): Step = given(predicate, Unit)

infix fun <T, R, S> (suspend T.() -> R).then(next: suspend T.() -> S): suspend T.() -> S = {
    this@then()
    next() }
infix fun <T, R, S> (suspend T.() -> R).after(prev: suspend T.() -> S): suspend T.() -> R = {
    prev()
    this@after() }
infix fun <T, R, S> (suspend T.() -> R).thru(next: suspend (R) -> S): suspend T.() -> S = {
    next(this@thru()) }
fun <T, R> (suspend T.() -> R).given(predicate: Predicate, fallback: R): suspend T.() -> R = {
    if (predicate()) this@given() else fallback }

infix fun <R, S> (() -> R).then(next: () -> S): () -> S = {
    this@then()
    next() }
infix fun <R, S> (() -> R).thru(next: (R) -> S): () -> S = {
    next(this@thru()) }
fun <R> (() -> R).given(predicate: Predicate, fallback: R): () -> R = {
    if (predicate()) this@given() else fallback }
infix fun AnyFunction.given(predicate: Predicate): AnyFunction = given(predicate, Unit)

infix fun <T, R, S> ((T) -> R).thru(next: (R) -> S): (T) -> S = {
    next(this@thru(it)) }

fun <R> KCallable<R>.with(vararg args: Any?): () -> R = {
    this@with.call(*args) }
fun <R> call(vararg args: Any?): (KCallable<R>) -> R = {
    it.call(*args) }

val currentThread
    get() = Thread.currentThread()
val mainThread = currentThread
fun Thread.isMainThread() = this === mainThread
fun onMainThread() = currentThread.isMainThread()

private fun newThread(group: ThreadGroup, name: String, priority: Int, target: Runnable) = Thread(group, target, name).also { it.priority = priority }
private fun newThread(name: String, priority: Int, target: Runnable) = Thread(target, name).also { it.priority = priority }
private fun newThread(priority: Int, target: Runnable) = Thread(target).also { it.priority = priority }

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Event(val transit: Short = 0) {
    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    annotation class Listening(
        val timeout: Long = 0L,
        val channel: Short = 0)

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    annotation class Signaling(val channel: Short = 0)

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    annotation class Retrying(
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

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Tag(
    val string: String,
    val keep: Boolean = true)

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
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTreeRoot

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTree(
    val branch: String = "",
    val level: UByte = 0u)

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Scope(val type: KClass<out CoroutineScope> = Scheduler::class)

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class LaunchScope

private val KCallable<*>.tag
    get() = annotations.find { it is Tag } as? Tag
private val KCallable<*>.event
    get() = annotations.find { it is Event } as? Event
private val KCallable<*>.schedulerScope
    get() = annotations.find { it is Scope } as? Scope
private fun annotatedScopeOf(step: CoroutineStep) =
    trySafelyForResult { step.asCallable().schedulerScope!!.type.reconstruct(step) }
private val KCallable<*>.launchScope
    get() = annotations.find { it is LaunchScope } as? LaunchScope

private typealias DescriptiveStep = suspend SchedulerScope.(Job) -> Unit
private typealias JobPredicate = (Job) -> Boolean
private typealias SchedulerNode = KClass<out Annotation>
private typealias SchedulerPath = Array<KClass<out Throwable>>
private typealias SchedulerWork = Scheduler.() -> Unit

typealias SequencerScope = LiveDataScope<Step?>
private typealias SequencerStep = suspend SequencerScope.() -> Unit
private typealias StepObserver = Observer<Step?>
private typealias LiveStep = LiveData<Step?>
private typealias LiveStepFunction = () -> LiveStep?
private typealias CaptureFunction = AnyToAnyFunction
private typealias LiveWork = Triple<LiveStepFunction, CaptureFunction?, Boolean>
private typealias LiveSequence = MutableList<LiveWork>
private typealias LiveWorkPredicate = (LiveWork) -> Boolean
private typealias SequencerWork = Sequencer.() -> Unit

typealias PropertyPredicate = suspend (KProperty<*>) -> Boolean
typealias PropertyCondition = suspend (KProperty<*>, Step) -> Unit

private typealias RunnableList = MutableList<Runnable>
private typealias MessageFunction = (Message) -> Any?
typealias Work = () -> Unit
typealias Step = suspend () -> Unit
typealias CoroutineStep = suspend CoroutineScope.() -> Unit

val emptyStep: Step = {}

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
fun KMutableProperty<*>.expire() = setter.call(null)

private interface ObjectReference<T> { val obj: T }
private interface NullReference<T> : ObjectReference<T?>
private fun <T> T.asObjRef() =
    object : ObjectReference<T> {
        override val obj: T
            get() = this@asObjRef }
private fun <T> T?.asNullRef() =
    object : NullReference<T> {
        override val obj: T?
            get() = this@asNullRef }
fun <T> T.asCallable(): KCallable<T> = asObjRef()::obj
fun <T> T?.asNullable(): KCallable<T?> = asNullRef()::obj

private typealias JobKProperty = KMutableProperty<Job?>
private typealias ResolverKClass = KClass<out Resolver>
private typealias ResolverKProperty = KMutableProperty<out Resolver?>
private typealias UnitKFunction = KFunction<Unit>

private typealias ID = Short
sealed interface State {
    object Failed : Resolved
    object Succeeded : Resolved
    object Pending : Unresolved, Ambiguous
    object Suspending : Ambiguous
    interface Resolved : State {
        companion object : Resolved
    }
    interface Unresolved : State {
        companion object : Unresolved
    }
    interface Ambiguous : State {
        companion object : Ambiguous
    }
    companion object {
        operator fun invoke(): State = Lock.Open
        fun of(string: String): State = Ambiguous
        operator fun get(id: ID): State = Lock.Open
        operator fun set(id: ID, lock: Any) {
            when (id.toInt()) {
                1 -> if (lock is Resolved) Scheduler.windDownClock()
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
    operator fun invoke(vararg param: Any?): Lock = this as Lock
    operator fun inc() = this
    operator fun dec() = this
    operator fun get(id: ID) = this
    operator fun set(id: ID, state: Any) {}
    operator fun plus(state: Any): State {
        when {
            this === State[2] && state is Pending ->
                if (service?.hasMoreInitWork == false)
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