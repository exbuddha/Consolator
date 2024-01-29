package net.consolator

import android.content.*
import android.os.*
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
import net.consolator.BaseActivity.*
import net.consolator.application.*
import java.util.LinkedList
import net.consolator.Scheduler.EventBus
import net.consolator.Scheduler.Lock
import net.consolator.Scheduler.Sequencer
import net.consolator.State.Resolved
import net.consolator.Scheduler.defer
import android.app.Service.START_NOT_STICKY
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.Unconfined
import net.consolator.Scheduler.clock
import net.consolator.Scheduler.sequencer

interface ResolverScope : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Scheduler
    fun commit(step: CoroutineStep): Any?
}

sealed interface SchedulerScope : ResolverScope {
    override fun commit(step: CoroutineStep) = net.consolator.commit(step)
}

private interface Synchronizer<T> {
    fun <R> synchronize(lock: T? = null, block: () -> R): R
}

private interface AttachOperator<in S> {
    fun attach(step: S, vararg args: Any?): Any?
    fun attach(index: Int, step: S, vararg args: Any?): Any?
}

fun commit(step: CoroutineStep) =
    (service ?:
    annotatedScopeOf(step) ?:
    foregroundLifecycleOwner?.lifecycleScope ?:
    Scheduler).let { scope ->
        (scope::class.memberFunctions.find {
            it.name == "commit" &&
            it.parameters.size == 2 &&
            it.parameters[1].name == "step"
        } ?: Scheduler::commit).call(scope, step) } // message queue reconfiguration
fun commit(vararg context: Any?): Any? =
    when (val task = context.firstOrNull()) {
        (task === START) -> {
            Scheduler.observe()
            clock = Clock(SVC, Thread.MAX_PRIORITY) @Tag(CLOCK_INIT) @Synchronous {
                // turn clock until scope is active
                info(SVC_TAG, "Clock is detected.")
            }.alsoStart()
            with(foregroundContext) {
                startService(intendFor(BaseService::class)
                    .putExtra(START_TIME_KEY, startTime()))
            } }
        else -> Unit }

object Scheduler : SchedulerScope, CoroutineContext, MutableLiveData<Step?>(), StepObserver, Synchronizer<Step>, AttachOperator<CoroutineStep>, (SchedulerWork) -> Unit {
    sealed interface BaseServiceScope : ResolverScope, IBinder, (Intent?) -> IBinder, VolatileContext, UniqueContext {
        override fun invoke(intent: Intent?): IBinder {
            mode = getModeExtra(intent)
            if (intent !== null && intent.hasCategory(START_TIME_KEY))
                clock?.start()
            if (State[2] !is Resolved) commit @Tag(INIT) @Synchronous {
                trySafelyForResult { getStartTimeExtra(intent) }?.let {
                    startTime = it }
                Sequencer {
                    if (logDb === null)
                        unconfined(true)
                            @Tag(STAGE_BUILD_LOG_DB) { self ->
                            coordinateBuildDatabase(self,
                                ::logDb,
                                stage = Context::stageLogDbCreated) }
                    if (netDb === null)
                        unconfined(true)
                            @Tag(STAGE_BUILD_NET_DB) { self ->
                            coordinateBuildDatabase(self,
                                ::netDb,
                                step = arrayOf(@Tag(STAGE_INIT_NET_DB) suspend {
                                    updateNetworkCapabilities()
                                    updateNetworkState() }),
                                stage = Context::stageNetDbInitialized) }
                    synchronize { resume() }
                } }
            return this }

        private suspend inline fun <reified D : RoomDatabase> SequencerScope.coordinateBuildDatabase(identifier: Any?, instance: KMutableProperty<out D?>, noinline stage: ContextStep?) =
            returnItsTag(identifier)?.let { tag ->
                blockAsyncOrResetByTag(instance, tag, {
                    buildDatabaseOrResetByTag(instance, tag) },
                    post = markTagsForReform(tag, stage, synchronize(identifier, stage), currentJob())) }
        private suspend inline fun <reified D : RoomDatabase> SequencerScope.coordinateBuildDatabase(identifier: Any?, instance: KMutableProperty<out D?>, vararg step: AnyStep, noinline stage: ContextStep?) =
            returnItsTag(identifier)?.let { tag ->
                blockAsyncOrResetByTag(instance, tag, {
                    buildDatabaseOrResetByTag(instance, tag) },
                    post = markTagsForReform(tag, stage, synchronize(identifier, *step, stage = stage), currentJob())) }
        private suspend inline fun <reified D : RoomDatabase> SequencerScope.buildDatabaseOrResetByTag(instance: KMutableProperty<out D?>, tag: String) {
            ref?.get()?.run<Context, D?> {
                sequencer { resetByTagOnError(tag, ::buildDatabase) } }?.let { new ->
                    instance.set(new) } }

        private suspend fun SequencerScope.blockAsyncOrResetByTag(lock: AnyKProperty, tag: String, block: AnyStep, post: AnyStep, condition: PropertyCondition) =
            blockAsyncOrResetByTag(lock, tag) {
                block()
                condition(lock, tag, post) }
        private suspend fun SequencerScope.blockAsyncOrResetByTag(lock: AnyKProperty, tag: String, block: AnyStep, post: AnyStep) =
            blockAsyncOrResetByTag(lock, tag, block, post, ::whenNotNullOrResetByTag)
        private suspend fun SequencerScope.blockAsyncOrResetByTag(lock: AnyKProperty, tag: String, block: AnyStep) =
            blockAsyncForResult(lock, block) { resetByTag(tag) }

        private fun SequencerScope.synchronize(identifier: Any?, stage: ContextStep?) =
            if (stage !== null) form(stage)
            else ignore
        private fun SequencerScope.synchronize(identifier: Any?, vararg step: AnyStep, stage: ContextStep?) =
            if (stage !== null) form(stage, *step)
            else ignore

        private val ignore get() = @Tag(IGNORE) emptyStep

        private fun SequencerScope.form(stage: ContextStep) = suspend { change(stage) }
        private fun SequencerScope.form(stage: ContextStep, vararg step: AnyStep) = step.first() then form(stage)

        private fun markTagsForReform(tag: String, stage: ContextStep?, form: AnyStep, job: Job) =
            form after { markTagsForCtxReform(tag, stage, form, job) }

        override fun commit(step: CoroutineStep) =
            attach(step.markTagForSvcCommit(), ::handleAhead)

        fun getStartTimeExtra(intent: Intent?) =
            intent?.getLongExtra(START_TIME_KEY, foregroundContext.asUniqueContext()?.startTime ?: now())

        var mode: Int?
        fun getModeExtra(intent: Intent?) =
            intent?.getIntExtra(MODE_KEY, mode ?: START_NOT_STICKY)

        fun clearObjects() {
            mode = null }

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
    ) : HandlerThread(name, priority), Synchronizer<Any>, AttachOperator<Runnable> {
        var handler: Handler? = null
        private var queue: RunnableList = LinkedList()

        constructor() : this(CLK)
        constructor(callback: Runnable) : this() {
            queue.add(callback) }
        constructor(name: String, priority: Int = currentThread.priority, callback: Runnable) : this(name, priority) {
            queue.add(callback) }

        var id = -1
        override fun start() {
            commitAsync(this, { !isAlive }) {
                id = synchronized(Companion) {
                    add(queue)
                    size }
                super.start()
            } }
        fun alsoStart(): Clock {
            start()
            return this }

        override fun run() {
            hLock = Lock.Open()
            handler = object : Handler(looper) {
                override fun handleMessage(msg: Message) =
                    DEFAULT_HANDLE(msg) }
            queue.run() }
        private fun turn(msg: Message) =
            if (isSynchronized(msg))
                synchronize(msg) {
                    if (queue.run(msg, false))
                        msg.callback.run() }
            else msg.callback.exec()
        private fun RunnableList.run(msg: Message? = null, isNotLocked: Boolean = true): Boolean {
            precursorOf(msg).onEach { callback ->
                callback.exec(isNotLocked)
                synchronized(sLock) {
                    // readjust by attach remarks and use index instead
                    remove(callback)
                } }
            return true
        }
        private fun precursorOf(msg: Message?) = queue
        private fun Runnable.exec(isNotLocked: Boolean = true) {
            if (isNotLocked && isSynchronized(this))
                synchronize(block = ::run)
            else run() }
        private fun isSynchronized(msg: Message) =
            isSynchronized(msg.callback) ||
            msg.asCallable().isSynchronized()
        private fun isSynchronized(callback: Runnable) =
            stepOf(callback).asNullable().isSynchronized() ||
            callback.asCallable().isSynchronized() ||
            callback::run.isSynchronized()
        private fun AnyKCallable.isSynchronized() =
            annotations.any { it is Synchronous }

        private lateinit var hLock: Lock
        override fun <R> synchronize(lock: Any?, block: () -> R) =
            synchronized(hLock(lock, block)) {
                hLock = Lock.Closed(lock, block)
                block().also {
                    hLock = Lock.Open(lock, block, it) } }

        private var sLock = Any()
        override fun attach(step: Runnable, vararg args: Any?) =
            synchronized<Unit>(sLock) { with(queue) {
                add(step)
                markTagsForClkAttach(size, step) } }
        override fun attach(index: Int, step: Runnable, vararg args: Any?) =
            synchronized<Unit>(sLock) {
                queue.add(index, step)
                markTagsForClkAttach(index, step) }

        var post = fun(callback: Runnable) =
            handler?.post(callback) ?:
            attach(callback)
        var postAhead = fun(callback: Runnable) =
            handler?.postAtFrontOfQueue(callback) ?:
            attach(0, callback)

        fun clearObjects() {
            handler = null
            queue.clear() }

        companion object : MutableList<RunnableList> by mutableListOf() {
            inline fun <reified R> scheduled(noinline step: CoroutineStep): R? = when (R::class) {
                Any::class -> (runnableOf(step) ?: msgOf(step)).asType()
                Runnable::class -> runnableOf(step).asType()
                Message::class -> msgOf(step).asType()
                else -> estimatedDelayOf(step).asType() }
            fun msgOf(step: CoroutineStep): Message? = null
            fun runnableOf(step: CoroutineStep): Runnable? = null
            fun stepOf(callback: Runnable): CoroutineStep? = null

            fun estimatedDelayOf(step: CoroutineStep): Long? = null
            fun delayOf(step: CoroutineStep): Long? = null
            fun timeOf(step: CoroutineStep): Long? = null

            private val DEFAULT_HANDLE: HandlerFunction = { turn(it) }
        }
    }

    class Sequencer : Synchronizer<LiveWork>, AttachOperator<LiveWork> {
        /* when a step is identified by tag as thread-blocking, it may only run safely on the clock;
        // otherwise, it may be attached to the current synchronizer scope. */
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
            get() = with(queue) {
                if (size > 0) removeFirst() /* readjusted by latest attach remarks */
                else field }
        private val work
            get() = synchronize { seq[ln] }
        private val queue: MutableList<Int> = LinkedList()
        private var latestStep: LiveStep? = null
        private var latestCapture: Any? = null

        fun setLifecycleOwner(index: Int, owner: LifecycleOwner) {}
        fun unsetLifecycleOwner(index: Int) {}
        private fun LiveWork.hasLifecycleOwner() = false
        private fun lifecycleOwnerOf(work: LiveWork): LifecycleOwner = TODO()

        private val mLock: Any get() = seq
        override fun <R> synchronize(lock: LiveWork?, block: () -> R) = synchronized(mLock, block)

        private fun init() {
            ln = -1
            clearFlags()
            clearLatestObjects() }
        fun start() {
            init()
            resume() }
        fun resume(index: Int) {
            ln = index
            resume() }
        fun resume(tag: String) {
            fun indexOf(tag: String): Int = TODO()
            resume(indexOf(tag)) }
        fun resume() {
            isActive = true
            advance() }
        var activate = fun() = Unit
        fun jump(index: Int) =
            if (hasError) null
            else synchronize {
                /* readjust index by attach remarks */
                (index >= 0 && index < seq.size && (!isObserving || seq[index].isAsynchronous())).also { allowed ->
                    if (allowed) queue.add(index) } }
        var next = fun(index: Int) = jump(index)
        private fun advance() {
            activate()
            while (next(ln) ?: return)
                work.let { run(it) ?: bypass(it) } || return
            isCompleted = finish() }
        fun observe(work: LiveWork): Boolean? {
            val (step, _, async) = work
            try {
                val step = step() // process tags to reuse live step
                latestStep = step // live step <-> work
                if (step !== null)
                    if (work.hasLifecycleOwner())
                        step.observe(lifecycleOwnerOf(work), observer)
                    else step.observeForever(observer)
                else return null
            } catch (ex: Throwable) {
                error(ex)
                return false }
            isObserving = true
            return async }
        var run = fun(work: LiveWork) = observe(work)
        fun capture(work: LiveWork): Boolean {
            val capture = work.second
            latestCapture = capture
            val async = capture?.invoke(work)
            return if (async is Boolean) async
            else false }
        var bypass = fun(work: LiveWork) = capture(work)
        fun end() = queue.isEmpty() && !isObserving
        var finish = fun() = end()

        fun reset(step: LiveStep? = latestStep) {
            step?.removeObserver(observer)
            isObserving = false }
        fun resetByTag(tag: String) {}

        fun cancel(ex: Throwable) {
            isCancelled = true
            this.ex = ex }
        fun cancelByTag(tag: String, ex: Throwable) = cancel(ex)
        fun error(ex: Throwable) {
            hasError = true
            this.ex = ex }
        fun errorByTag(tag: String, ex: Throwable) = error(ex)
        var interrupt = fun(ex: Throwable) = ex
        var interruptByTag = fun(tag: String, ex: Throwable) = ex

        suspend inline fun <R> SequencerScope.resetOnCancel(block: () -> R) =
            try { block() }
            catch (ex: CancellationException) {
                reset()
                cancel(ex)
                throw interrupt(ex) }
        suspend inline fun <R> SequencerScope.resetOnError(block: () -> R) =
            try { block() }
            catch (ex: Throwable) {
                reset()
                error(ex)
                throw interrupt(ex) }
        suspend inline fun <R> SequencerScope.resetByTagOnCancel(tag: String, block: () -> R) =
            try { block() }
            catch (ex: CancellationException) {
                resetByTag(tag)
                cancelByTag(tag, ex)
                throw interruptByTag(tag, ex) }
        suspend inline fun <R> SequencerScope.resetByTagOnError(tag: String, block: () -> R) =
            try { block() }
            catch (ex: Throwable) {
                resetByTag(tag)
                errorByTag(tag, ex)
                throw interruptByTag(tag, ex) }

        // preserve tags
        private fun resettingFirstly(step: SequencerStep) = step after { reset() }
        private fun resettingLastly(step: SequencerStep) = step then { reset() }
        private fun resettingByTagFirstly(step: SequencerStep) = step after { resetByTag(tagOf(step)) }
        private fun resettingByTagLastly(step: SequencerStep) = step then { resetByTag(tagOf(step)) }
        private fun tagOf(step: SequencerStep) = returnItsTag(step)!!

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
            clearError() }
        fun clearError() {
            hasError = false
            ex = null }
        fun clearLatestObjects() {
            latestStep = null
            latestCapture = null }
        fun clearObjects() {
            seq.clear()
            queue.clear()
            clearLatestObjects() }

        companion object : (SequencerWork) -> Unit? {
            override fun invoke(work: SequencerWork) = sequencer?.work()
            const val ATTACHED_ALREADY = -1
        }

        private fun LiveSequence.attach(element: LiveWork) =
            add(element)
        private fun LiveSequence.attach(index: Int, element: LiveWork) =
            add(index, element)
        override fun attach(step: LiveWork, vararg args: Any?) = synchronize { with(seq) {
            attach(step)
            size - 1 }.also { index ->
                markTagsForSeqAttach(args.firstOrNull(), index, step) } }
        fun attachOnce(work: LiveWork) = synchronize {
            if (work.isNotAttached())
                attach(work)
            else ATTACHED_ALREADY }
        fun attachOnce(range: IntRange, work: LiveWork) = synchronize {
            if (work.isNotAttached(range))
                attach(work)
            else ATTACHED_ALREADY }
        fun attachOnce(first: Int, last: Int, work: LiveWork) = synchronize {
            if (work.isNotAttached(first, last))
                attach(work)
            else ATTACHED_ALREADY }
        override fun attach(index: Int, step: LiveWork, vararg args: Any?) = synchronize { with(seq) {
            if (ln in index..size)
                ln += 1 // also, remark previously tagged works
            attach(index, step)
            markTagsForSeqAttach(args.firstOrNull(), index, step)
            index } }
        fun attachOnce(index: Int, work: LiveWork) = synchronize {
            if (work.isNotAttached(index)) {
                attach(index, work)
                index }
            else ATTACHED_ALREADY }
        fun attachOnce(range: IntRange, index: Int, work: LiveWork) = synchronize {
            if (work.isNotAttached(range, index))
                attach(index, work)
            else ATTACHED_ALREADY }
        fun attachOnce(first: Int, last: Int, index: Int, work: LiveWork) = synchronize {
            if (work.isNotAttached(first, last, index))
                attach(index, work)
            else ATTACHED_ALREADY }
        fun attachAfter(work: LiveWork, tag: String? = null) =
            attach(after, work, tag)
        fun attachBefore(work: LiveWork, tag: String? = null) =
            attach(before, work, tag)
        fun attachOnceAfter(work: LiveWork) =
            attachOnce(after, work)
        fun attachOnceBefore(work: LiveWork) =
            attachOnce(before, work)

        private fun markTagsForLaunch(step: SequencerStep, index: IntFunction, context: CoroutineContext? = null) =
            step after { currentJob().let { job ->
                synchronize { markTagsForSeqLaunch(step, index() /* readjusted by attach remarks */, context, job) } } }

        fun attach(async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(block = { markTagsForLaunch(step, { index })(step) }) }.also {
                index = attach(it, returnItsTag(step)) } }
        fun attach(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(block = { markTagsForLaunch(step, { index })(step) }) }, capture, async).also {
                index = attach(it, returnItsTag(step)) } }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }.also {
                index = attach(it, returnItsTag(step)) } }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }, capture, async).also {
                index = attach(it, returnItsTag(step)) } }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = { markTagsForLaunch(step, { index })(step) }) }.also {
                attach(index, it, returnItsTag(step)) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = { markTagsForLaunch(step, { index })(step) }) }, capture, async).also {
                attach(index, it, returnItsTag(step)) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }.also {
                attach(index, it, returnItsTag(step)) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }, capture, async).also {
                attach(index, it, returnItsTag(step)) }
        fun attachAfter(async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(block = { markTagsForLaunch(step, { index })(step) }) }.also {
                index = attachAfter(it, returnItsTag(step)) } }
        fun attachAfter(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(block = { markTagsForLaunch(step, { index })(step) }) }, capture, async).also {
                index = attachAfter(it, returnItsTag(step)) } }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }.also {
                index = attachAfter(it, returnItsTag(step)) } }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }, capture, async).also {
                index = attachAfter(it, returnItsTag(step)) } }
        fun attachBefore(async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(block = { markTagsForLaunch(step, { index })(step) }) }.also {
                index = attachBefore(it, returnItsTag(step)) } }
        fun attachBefore(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(block = { markTagsForLaunch(step, { index })(step) }) }, capture, async).also {
                index = attachBefore(it, returnItsTag(step)) } }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
            var index = -1
            return stepToNull(async) { liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }.also {
                index = attachBefore(it, returnItsTag(step)) } }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
            var index = -1
            return Triple({ liveData(context, block = { markTagsForLaunch(step, { index }, context)(step) }) }, capture, async).also {
                index = attachBefore(it, returnItsTag(step)) } }

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

        private fun stepToNull(async: Boolean = false, step: LiveStepFunction) = Triple(step, nullBlock, async)
        private fun nullStepTo(block: CaptureFunction) = Triple(nullStep, block, false)
        private val nullStep: LiveStepFunction = @Tag(NULL_STEP) { null }
        private val nullBlock: CaptureFunction? = null

        private fun LiveWork.isAsynchronous() = third

        private fun LiveWork.isSameWork(work: LiveWork) =
            this === work || (first === work.first && second === work.second)
        private fun LiveWork.isNotSameWork(work: LiveWork) =
            this !== work || first !== work.first || second !== work.second
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

    var clock: Clock? = null
        get() = field.singleton().also { field = it }
    var sequencer: Sequencer? = null
        get() = field.singleton().also { field = it }

    fun <T : Resolver> defer(resolver: KClass<out T>, provider: Any = resolver, vararg context: Any?): Unit? {
        fun ResolverKProperty.setResolverThenCommit() =
            reconstruct(provider).get()?.commit(context)
        return when (resolver) {
            MigrationManager::class ->
                ::applicationMigrationManager.setResolverThenCommit()
            ConfigurationChangeManager::class ->
                ::activityConfigurationChangeManager.setResolverThenCommit()
            NightModeChangeManager::class ->
                ::activityNightModeChangeManager.setResolverThenCommit()
            LocalesChangeManager::class ->
                ::activityLocalesChangeManager.setResolverThenCommit()
            MemoryManager::class ->
                ::applicationMemoryManager.setResolverThenCommit()
            else -> null
        } }

    private var activityConfigurationChangeManager: ConfigurationChangeManager? = null
    private var activityNightModeChangeManager: NightModeChangeManager? = null
    private var activityLocalesChangeManager: LocalesChangeManager? = null
    var applicationMigrationManager: MigrationManager? = null
    var applicationMemoryManager: MemoryManager? = null

    fun windDownClock() {
        clock?.apply {
            Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_DEFAULT)
        } }
    fun windDown() {
        windDownClock()
        sequencer = null }

    fun clearResolverObjects() {
        activityConfigurationChangeManager = null
        activityNightModeChangeManager = null
        activityLocalesChangeManager = null
        applicationMigrationManager = null
        applicationMemoryManager = null }
    fun clearObjects() {
        clock = null
        sequencer = null }

    override val coroutineContext
        get() = IO

    override fun commit(step: CoroutineStep) =
        attach(step.markTagForSchCommit())
    override fun attach(step: CoroutineStep, vararg args: Any?): Any? {
        val enlist: CoroutineFunction? = (args.firstOrNull() ?: ::handle).asType()
        val transfer: CoroutineFunction? = (args.secondOrNull() ?: ::reattach).asType()
        return when (val result = enlist?.invoke(step)) {
            null, false -> transfer?.invoke(@Unlisted step)
            true, is Job -> result
            else -> transfer?.invoke(@Enlisted step) } }
    override fun attach(index: Int, step: CoroutineStep, vararg args: Any?) =
        attach(step, *args)
    private fun reattach(step: CoroutineStep, handler: CoroutineFunction = ::launch) =
        trySafelyForResult { detach(step) }?.run(handler)
    private fun detach(step: CoroutineStep) =
        Clock.scheduled<Any>(step)?.detach()?.asCoroutine() ?: step

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R {
        return operation(initial, SchedulerElement)
    }
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        return null
    }
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        return this
    }

    override fun onChanged(value: Step?) {
        value.markTagForSchExec()?.run { synchronize(this, ::block) } }
    override fun <R> synchronize(lock: Step?, block: () -> R) = block() // or apply (live step) capture function internally

    object EventBus : Buffer() {
        override suspend fun collectSafely(collector: FlowCollector<Any?>) {
            // emit signalled events to collector
        }

        fun signal(step: ContextStep): Boolean {
            // record context event
            return true
        }
        fun signal(event: Transit): Boolean {
            // record signal event
            return true
        }
    }

    enum class Lock : State { Closed, Open }

    override fun invoke(work: SchedulerWork) = this.work()
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class Buffer : AbstractFlow<Any?>()

private typealias Transit = Short?
val Any?.transit: Transit
    get() = when (this) {
        is Relay -> transit
        is Number -> toShort()
        else -> asNullable().event?.transit }

fun Step.relay(transit: Transit = this.transit) = Relay(transit)
fun Step.reevaluate(transit: Transit = this.transit) = object : Relay(transit) {
    override suspend fun invoke() = this@reevaluate() }

open class Relay(val transit: Transit = null) : Step {
    override suspend fun invoke() {}
}

inline fun <reified T : Resolver> LifecycleOwner.defer(member: UnitKFunction, vararg context: Any?) =
    defer(T::class, this, member, *context)
inline fun <reified T : Resolver> LifecycleOwner.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    defer(T::class, this, member, *context, `super`)
inline fun <reified T : Resolver> Context.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    defer(T::class, this, member, *context, `super`)
inline fun <reified T : Resolver> BaseActivity.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    (this as Context).defer<T>(member, *context, `super` = `super`)

interface Resolver : ResolverScope {
    override fun commit(step: CoroutineStep) =
        commit(blockOf(step))
    fun commit(vararg context: Any?) =
        context.lastOrNull().asWork()?.invoke()
}

fun schedule(step: Step) = Scheduler.postValue(step)
fun scheduleAhead(step: Step) { Scheduler.value = step }

// runnable <-> message
fun post(callback: Runnable) = clock?.post?.invoke(callback)
fun postAhead(callback: Runnable) = clock?.postAhead?.invoke(callback)
private fun tagOf(callback: Runnable): String? = TODO()
private fun tagOf(msg: Message): String? = TODO()
private fun tagOf(what: Int): String? = TODO()

// step <-> runnable
fun handle(step: CoroutineStep) = post(runnableOf(step))
fun handleAhead(step: CoroutineStep) = postAhead(runnableOf(step))
fun handleSafely(step: CoroutineStep) = post(safeRunnableOf(step))
fun handleAheadSafely(step: CoroutineStep) = postAhead(safeRunnableOf(step))
fun handleInterrupting(step: CoroutineStep) = post(interruptingRunnableOf(step))
fun handleAheadInterrupting(step: CoroutineStep) = postAhead(interruptingRunnableOf(step))
fun handleSafelyInterrupting(step: CoroutineStep) = post(safeInterruptingRunnableOf(step))
fun handleAheadSafelyInterrupting(step: CoroutineStep) = postAhead(safeInterruptingRunnableOf(step))

fun <T> blockOf(step: suspend CoroutineScope.() -> T): () -> T = { runBlocking(block = step) }
fun <T> runnableOf(step: suspend CoroutineScope.() -> T) = Runnable { runBlocking(block = step) }
fun <T> safeRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafely(blockOf(step)) }
fun <T> interruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { tryInterrupting(step) }
fun <T> safeInterruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafelyInterrupting(step) }

inline fun <R> commitAsync(lock: Any, predicate: Predicate, block: () -> R) {
    if (predicate())
        synchronized(lock) {
            if (predicate()) block() } }
inline fun <R, S : R> commitAsyncForResult(lock: Any, predicate: Predicate, block: () -> R, fallback: () -> S? = { null }): R? {
    if (predicate())
        synchronized(lock) {
            if (predicate()) return block() }
    return fallback() }

inline fun <R> blockAsync(lock: Any, crossinline predicate: Predicate, crossinline block: suspend () -> R) {
    if (predicate())
        synchronized(lock) {
            runBlocking {
                if (predicate()) block() } } }
inline fun <R, S : R> blockAsyncForResult(lock: Any, crossinline predicate: Predicate, crossinline block: suspend () -> R, noinline fallback: suspend () -> S? = { null }) =
    if (predicate())
        synchronized(lock) {
            runBlocking {
                if (predicate()) block()
                else fallback() } }
    else fallback.block()

inline fun <R> blockAsync(lock: AnyKProperty, crossinline block: suspend () -> R) =
    blockAsync(lock, lock::isNotNull, block)
inline fun <R, S : R> blockAsyncForResult(lock: AnyKProperty, crossinline block: suspend () -> R, noinline fallback: suspend () -> S? = { null }) =
    blockAsyncForResult(lock, lock::isNotNull, block, fallback)

private fun Any.detach() = when (this) {
    is Runnable -> detach()
    is Message -> detach()?.asRunnable()
    else -> null }

private fun Runnable.asCoroutine(): CoroutineStep = TODO()
private fun Runnable.asMessage() =
    with(Clock) { stepOf(this@asMessage)?.let { msgOf(it) } }
private fun Runnable.detach(): Runnable? = null
private fun Runnable.reattach() {}
private fun Runnable.close() {}

private fun Message.asCoroutine(): CoroutineStep = TODO()
private fun Message.asRunnable() = callback
private fun Message.detach(): Message? = null
private fun Message.reattach() {}
private fun Message.close() {}

private operator fun Message.get(tag: String): Any? = TODO()
private operator fun Message.set(tag: String, value: Any?) {}

inline fun <R> sequencer(block: Sequencer.() -> R) = sequencer?.block()
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

fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observer: Observer<T> = disposer(this)): Observer<T> {
    first.observe(owner, observer)
    return observer }
fun <T, R> Pair<LiveData<T>, (T) -> R>.dispose(owner: LifecycleOwner, disposer: Observer<T> = owner.disposer(this)) =
    observe(owner, disposer)
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: Observer<T> = disposer(this)): Observer<T> {
    first.observeForever(observer)
    return observer }
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observer: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposer) =
    observe(owner, observer(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposer) =
    observe(observer(this))
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObserver(observer: Observer<T>) =
    first.removeObserver(observer)
fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObservers(owner: LifecycleOwner) =
    first.removeObservers(owner)

fun <T, R> captor(liveStep: Pair<LiveData<T>, (T) -> R>) =
    Observer<T> { liveStep.second(it) }
private fun <T, R> disposer(liveStep: Pair<LiveData<T>, (T) -> R>) =
    object : Observer<T> {
        override fun onChanged(value: T) {
            val (step, capture) = liveStep
            step.removeObserver(this)
            capture(value) } }
private fun <T, R> LifecycleOwner.disposer(liveStep: Pair<LiveData<T>, (T) -> R>) =
    Observer<T> { value ->
        val (step, capture) = liveStep
        step.removeObservers(this)
        capture(value) }

suspend fun SequencerScope.change(stage: ContextStep) = resetByTag(tagOf(stage)) {
    EventBus.signal(stage) }
suspend fun SequencerScope.change(event: Transit) = reset {
    EventBus.signal(event) }

suspend fun SequencerScope.reset() = net.consolator.reset()
suspend fun SequencerScope.resetByTag(tag: String) = net.consolator.resetByTag(tag)

suspend fun <R> SequencerScope.capture(capture: () -> R) = emit {
    reset()
    capture() }
suspend fun <R> SequencerScope.captureByTag(tag: String, capture: () -> R) = emit {
    resetByTag(tag)
    capture() }

private suspend inline fun <R> SequencerScope.reset(block: () -> R): R {
    reset()
    return block() }
private suspend inline fun <R> SequencerScope.resetByTag(tag: String, block: () -> R): R {
    resetByTag(tag)
    return block() }

private fun reset() { sequencer?.reset() }
private fun resetByTag(tag: String) { sequencer?.resetByTag(tag) }
private fun tagOf(stage: ContextStep): String = TODO()

private suspend inline fun whenNotNull(instance: AnyKProperty, stage: String, block: AnyStep) {
    if (instance.isNotNull())
        block() }
private suspend inline fun whenNotNullOrResetByTag(instance: AnyKProperty, stage: String, block: AnyStep) =
    if (instance.isNotNull())
        block()
    else resetByTag(stage)

private interface SchedulerKey : CoroutineContext.Key<SchedulerElement> {
    companion object : SchedulerKey
}
private interface SchedulerElement : CoroutineContext.Element {
    companion object : SchedulerElement {
        override val key
            get() = SchedulerKey }
}

fun CoroutineScope.relaunch(instance: JobKProperty, context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)
fun LifecycleOwner.relaunch(instance: JobKProperty, context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)
private fun relaunch(launcher: JobKFunction, instance: JobKProperty, context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    instance.require({ !it.isActive }) {
        launcher.call(context, start, step)
    }.also { instance.markTag() }

fun launch(it: CoroutineStep) = launch(step = it)
fun launch(context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    ProcessLifecycleOwner.get().launch(context, start, step)
fun LifecycleOwner.launch(context: CoroutineContext = Scheduler, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep): Job {
    val (scope, task) = determineScopeAndCoroutine(context, start, step)
    val (context, start, step) = task
    return scope.launch(context, start, step after { job ->
        markTagsForJobLaunch(context, start, step, job) }) }

private fun LifecycleOwner.determineScopeAndCoroutine(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    determineScope(step).let { scope ->
        scope to scope.determineCoroutine(context, start, step) }
private fun LifecycleOwner.determineScope(step: CoroutineStep) =
    annotatedScopeOf(step) ?: lifecycleScope
private fun CoroutineScope.determineCoroutine(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    Triple(
        // context key <-> step
        if (context.isSchedulerContext()) context
        else Scheduler + context,
        start,
        step)
private fun CoroutineContext.isSchedulerContext() =
    this is Scheduler || this[SchedulerKey] is SchedulerElement

infix fun Job.then(next: SchedulerStep): CoroutineStep = {}
infix fun Job.after(prev: SchedulerStep): CoroutineStep = {}
infix fun Job.given(predicate: JobPredicate): CoroutineStep = {}
infix fun Job.otherwise(next: SchedulerStep): CoroutineStep = {}
infix fun Job.onCancel(action: SchedulerStep): CoroutineStep = {}
infix fun Job.onError(action: SchedulerStep): CoroutineStep = {}
infix fun Job.onTimeout(action: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.then(next: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.after(prev: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.given(predicate: JobPredicate): CoroutineStep = {}
infix fun CoroutineStep.otherwise(next: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.onCancel(action: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.onError(action: SchedulerStep): CoroutineStep = {}
infix fun CoroutineStep.onTimeout(action: SchedulerStep): CoroutineStep = {}

fun SchedulerScope.enact(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.error(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.retry(job: Job, exit: ThrowableFunction? = null) {}
fun SchedulerScope.close(job: Job, exit: ThrowableFunction? = null) {}

fun SchedulerScope.keepAlive(job: Job) = keepAliveNode(job.node)
fun SchedulerScope.keepAliveOrClose(job: Job) =
    job.node.let { node ->
        keepAliveNode(node) || job.close(node) }
fun SchedulerScope.keepAliveNode(node: SchedulerNode): Boolean = false

fun LifecycleOwner.detach(job: Job? = null) {}
fun LifecycleOwner.reattach(job: Job? = null) {}
fun LifecycleOwner.close(job: Job? = null) {}

fun LifecycleOwner.detach(node: SchedulerNode) {}
fun LifecycleOwner.reattach(node: SchedulerNode) {}
fun LifecycleOwner.close(node: SchedulerNode) {}

fun Job.close(node: SchedulerNode): Boolean = true
fun Job.close() {}

val Job.node: SchedulerNode
    get() = TODO()

fun SchedulerScope.change(stage: ContextStep) =
    EventBus.signal(stage)
fun SchedulerScope.change(event: Transit) =
    EventBus.signal(event)

fun <R> SchedulerScope.change(member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(ref: WeakContext, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)
fun <R> SchedulerScope.change(ref: WeakContext, owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.signal(stage)

suspend fun CoroutineScope.repeatSuspended(predicate: PredicateFunction = @Tag(IS_ACTIVE) { isActive }, block: JobFunction, delayTime: DelayFunction = @Tag(YIELD) { 0L }, scope: CoroutineScope = this) {
    markTagsForJobRepeat(predicate, block, delayTime, currentJob())
    while (predicate()) {
        block(scope)
        if (isActive)
            delayOrYield(delayTime()) } }
suspend fun delayOrYield(dt: Long = 0L) {
    if (dt > 0) delay(dt)
    else if (dt == 0L) yield() }

suspend fun CoroutineScope.currentContext(scope: CoroutineScope = this) =
    currentJob()[CONTEXT].asContext()!!
suspend fun CoroutineScope.registerContext(context: WeakContext, scope: CoroutineScope = this) {
    currentJob()[CONTEXT] = context }

typealias JobFunction = suspend (Any?) -> Unit
private typealias JobFunctionSet = MutableSet<Pair<String, Any>>
private var jobs: JobFunctionSet? = null

operator fun Job.get(tag: String) =
    jobs?.find { tag == it.first }?.second.asAnyArray()?.get(1)
operator fun Job.set(tag: String, value: Any?) {
    // addressable layer work
    value.markTag(tag) }

private fun JobFunctionSet.save(tag: String, function: AnyKCallable) = function.tag.apply {
    save(combineTags(tag, this?.string), this?.keep ?: true, function) }
private fun JobFunctionSet.save(tag: String, unit: String, function: AnyKCallable) {}
private fun JobFunctionSet.save(tag: Tag?, self: AnyKCallable) =
    if (tag !== null) with(tag) { save(string, keep, self) }
    else save(null, false, self)
private fun JobFunctionSet.save(tag: String?, keep: Boolean, function: AnyKCallable) =
    add((tag ?: currentThreadJob().hashCode().toString()) to arrayOf(keep, function)) // rewire related parts
private fun combineTags(tag: String, self: String?) =
    if (self === null) tag
    else "$tag.$self"

fun AnyKCallable.markTag() = tag.also { jobs?.save(it, this) }
fun Any.markTag() = asCallable().markTag()
private fun returnItsTag(it: Any?) = it.asNullable().tag?.string

fun Any?.markTag(tag: String) = jobs?.save(tag, asNullable())
fun Any?.markSequentialTag(vararg tag: String?): String? = TODO()
private fun <T> T.applyMarkTag(tag: String) = apply { markTag(tag) }

private fun Step?.markTagForSchExec() = applyMarkTag(SCH_EXEC)
private fun CoroutineStep.markTagForSchCommit() = applyMarkTag(SCH_COMMIT)
private fun CoroutineStep.markTagForSvcCommit() = applyMarkTag(SVC_COMMIT)

fun markTags(vararg function: Any?) {
    when (function.firstOrNull()) {
        JOB_LAUNCH ->
            markTagsForJobLaunch(*function, i = 1)
        SEQ_LAUNCH ->
            markTagsForSeqLaunch(*function, i = 1)
        JOB_REPEAT ->
            markTagsForJobRepeat(*function, i = 1)
        CLK_ATTACH ->
            markTagsForClkAttach(*function, i = 1)
        SEQ_ATTACH ->
            markTagsForSeqAttach(*function, i = 1)
        CTX_REFORM ->
            markTagsForCtxReform(*function, i = 1)
        else ->
            function.forEach {
                it.asNullable().markTag() } } }
private fun markTagsForJobLaunch(vararg function: Any?, i: Int = 0) =
    function[i + 2]?.markTag()?.also { step ->
        val stepTag = step.string
        val jobId = function[i + 4]?.let { job ->
            jobs?.save("$stepTag.$JOB", step.keep, job.asCallable())
            job.asJob().hashCode() } /* job */
        function[i]?.let { context ->
            jobs?.save("$stepTag@$jobId.$CONTEXT", false, context.asCallable()) } /* context */
        function[i + 1]?.let { start ->
            jobs?.save("$stepTag@$jobId.$START", false, start.asCallable()) } /* start */ }
private fun markTagsForJobRepeat(vararg function: Any?, i: Int = 0) =
    function[i + 1]?.markTag()?.also { blockTag ->
        val blockTag = blockTag.string
        val jobId = function[i + 3].asInt()
        function[i + 2]?.let { delay ->
            jobs?.save("$blockTag@$jobId.$DELAY", delay.asCallable()) } /* delay */
        function[i]?.let { predicate ->
            jobs?.save("$blockTag@$jobId.$PREDICATE", predicate.asCallable()) } /* predicate */ }
private fun markTagsForClkAttach(vararg function: Any?, i: Int = 0) =
    function[i + 1]?.let { step -> when (step) {
        is Runnable -> {
            val step = step.asRunnable()!!
            val stepTag = tagOf(step)
            jobs?.save("$stepTag.$CALLBACK", step.asCallable()) /* callback */
            function[i]?.let { index ->
                jobs?.save("$stepTag.$INDEX", index.asCallable()) } /* index */ }
        is Message -> {
            val step = step.asMessage()!!
            jobs?.save("${tagOf(step)}.$MSG", step.asCallable()) /* message */ }
        else -> {
            val step = step.asInt()!!
            jobs?.save("${tagOf(step)}.$WHAT", step.asCallable()) /* what */ } } }
private fun markTagsForSeqAttach(vararg function: Any?, i: Int = 0) =
    function[i]?.asString().let { stepTag ->
        val stepTag = stepTag ?: NULL_STEP
        function[i + 1]?.let { index ->
            jobs?.save("$stepTag.$INDEX", index.asCallable())
            function[i + 2]?.asLiveWork()?.let { work ->
                jobs?.save("$stepTag#$index.$WORK", work.asCallable()) } } /* index & work */ }
private fun markTagsForSeqLaunch(vararg function: Any?, i: Int = 0) =
    function[i]?.markTag()?.also { stepTag ->
        val stepTag = stepTag.string
        val index = function[i + 1]?.asInt()!! // optionally, readjust by remarks or from seq here instead
        val jobId = function[i + 3].asJob().hashCode()
        function[i + 2]?.let { context ->
            jobs?.save("$stepTag#$index@$jobId.$CONTEXT", false, context.asNullable()) } /* context */ }
private fun markTagsForCtxReform(vararg function: Any?, i: Int = 0) =
    function[i + 1].markSequentialTag(function[i].asString())?.also { stageTag ->
        val jobId = function[i + 3]?.let { job ->
            jobs?.save("$stageTag.$JOB", false, job.asCallable())
            job.asJob().hashCode() } /* job */
        function[i + 2]?.let { form ->
            jobs?.save("$stageTag@$jobId.$FORM", false, form.asCallable()) } /* form */ }

infix fun <R, S> (suspend () -> R).then(next: suspend () -> S): suspend () -> S = {
    this@then()
    next() }
infix fun <R, S> (suspend () -> R).after(prev: suspend () -> S): suspend () -> R = {
    prev()
    this@after() }
infix fun <R, S> (suspend () -> R).thru(next: suspend (R) -> S): suspend () -> S = {
    next(this@thru()) }
fun <R> (suspend () -> R).given(predicate: Predicate, fallback: suspend () -> R): suspend () -> R = {
    if (predicate()) this@given() else fallback() }
infix fun Step.given(predicate: Predicate) = given(predicate, emptyStep)

infix fun <T, R, S> (suspend T.() -> R).then(next: suspend T.() -> S): suspend T.() -> S = {
    this@then()
    next() }
infix fun <T, R, S> (suspend T.() -> R).after(prev: suspend T.() -> S): suspend T.() -> R = {
    prev()
    this@after() }
infix fun <T, R, S> (suspend T.() -> R).thru(next: suspend (R) -> S): suspend T.() -> S = {
    next(this@thru()) }
fun <T, R> (suspend T.() -> R).given(predicate: Predicate, fallback: suspend T.() -> R): suspend T.() -> R = {
    if (predicate()) this@given() else fallback() }

infix fun <T, U, R, S> (suspend T.(U) -> R).then(next: suspend T.(U) -> S): suspend T.(U) -> S = {
    this@then(it)
    next(it) }
infix fun <T, U, R, S> (suspend T.(U) -> R).after(prev: suspend T.(U) -> S): suspend T.(U) -> R = {
    prev(it)
    this@after(it) }
infix fun <T, U, R, S> (suspend T.(U) -> R).thru(next: suspend (R) -> S): suspend T.(U) -> S = {
    next(this@thru(it)) }
fun <T, U, R> (suspend T.(U) -> R).given(predicate: Predicate, fallback: suspend T.(U) -> R): suspend T.(U) -> R = {
    if (predicate()) this@given(it) else fallback(it) }

infix fun <R, S> (() -> R).then(next: () -> S): () -> S = {
    this@then()
    next() }
infix fun <R, S> (() -> R).after(prev: () -> S): () -> R = {
    prev()
    this@after() }
infix fun <R, S> (() -> R).thru(next: (R) -> S): () -> S = {
    next(this@thru()) }
fun <R> (() -> R).given(predicate: Predicate, fallback: () -> R): () -> R = {
    if (predicate()) this@given() else fallback() }
infix fun AnyFunction.given(predicate: Predicate) = given(predicate, emptyWork)

infix fun <T, R, S> ((T) -> R).thru(next: (R) -> S): (T) -> S = {
    next(this@thru(it)) }

fun <R> (suspend () -> R).block() = runBlocking { invoke() }
fun <T, R> (suspend T.() -> R).block(scope: T) = runBlocking { invoke(scope) }
fun <T, U, R> (suspend T.(U) -> R).block(scope: T, value: U) = runBlocking { invoke(scope, value) }

fun <R> KCallable<R>.with(vararg args: Any?): () -> R = {
    this@with.call(*args) }
fun <R> call(vararg args: Any?): (KCallable<R>) -> R = {
    it.call(*args) }

suspend fun currentJob() = currentCoroutineContext().job
fun currentThreadJob() = ::currentJob.block()

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
        val channel: Short = 0) {
        @Retention(SOURCE)
        @Target(EXPRESSION)
        annotation class OnEvent(val transit: Short)
    }

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
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Keep

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Path(
    val name: String = "",
    val route: SchedulerPath = []) {
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

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
private annotation class Synchronous(val node: SchedulerNode = Annotation::class)

@Retention(SOURCE)
@Target(EXPRESSION)
private annotation class Enlisted

@Retention(SOURCE)
@Target(EXPRESSION)
private annotation class Unlisted

private val AnyKCallable.tag
    get() = annotations.find { it is Tag } as? Tag
private val AnyKCallable.event
    get() = annotations.find { it is Event } as? Event
private val AnyKCallable.schedulerScope
    get() = annotations.find { it is Scope } as? Scope
private fun annotatedScopeOf(step: CoroutineStep) =
    trySafelyForResult { step.asCallable().schedulerScope!!.type.reconstruct(step) }
private val AnyKCallable.launchScope
    get() = annotations.find { it is LaunchScope } as? LaunchScope

private typealias SchedulerStep = suspend SchedulerScope.(Job) -> Unit
private typealias JobPredicate = (Job) -> Boolean
private typealias SchedulerNode = KClass<out Annotation>
private typealias SchedulerPath = Array<KClass<out Throwable>>
private typealias SchedulerWork = Scheduler.() -> Unit

private typealias SequencerScope = LiveDataScope<Step?>
private typealias SequencerStep = suspend SequencerScope.(Any?) -> Unit
private typealias StepObserver = Observer<Step?>
private typealias LiveStep = LiveData<Step?>
private typealias LiveStepFunction = () -> LiveStep?
private typealias CaptureFunction = AnyToAnyFunction
private typealias LiveWork = Triple<LiveStepFunction, CaptureFunction?, Boolean>
private typealias LiveSequence = MutableList<LiveWork>
private typealias LiveWorkPredicate = (LiveWork) -> Boolean
private typealias SequencerWork = Sequencer.() -> Unit

private typealias PropertyPredicate = suspend (AnyKProperty) -> Boolean
private typealias PropertyCondition = suspend (AnyKProperty, String, AnyStep) -> Any?

private typealias PredicateFunction = suspend () -> Boolean
private typealias DelayFunction = suspend () -> Long

private typealias MessageFunction = (Message) -> Any?
private typealias HandlerFunction = Clock.(Message) -> Unit
private typealias RunnableList = MutableList<Runnable>
private typealias CoroutineFunction = (CoroutineStep) -> Any?

typealias Work = () -> Unit
typealias Step = suspend () -> Unit
typealias AnyStep = suspend () -> Any?
typealias CoroutineStep = suspend CoroutineScope.() -> Unit
typealias Process = android.os.Process

val emptyWork = {}
val emptyStep = suspend {}

interface Expiry : MutableSet<Lifetime> {
    fun unsetAll(property: AnyKMutableProperty) {
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
typealias Lifetime = (AnyKMutableProperty) -> Boolean?
fun AnyKMutableProperty.expire() = set(null)

private interface ObjectReference<T> { val obj: T }
private interface NullReference<T> : ObjectReference<T?>
private fun <T> T.asObjRef() =
    object : ObjectReference<T> {
        override val obj get() = this@asObjRef }
private fun <T> T?.asNullRef() =
    object : NullReference<T> {
        override val obj get() = this@asNullRef }
fun <T> T.asCallable(): KCallable<T> = asObjRef()::obj
fun <T> T?.asNullable(): KCallable<T?> = asNullRef()::obj

fun trueWhenNull(it: Any?) = it === null

fun Any?.asMessage() = asType<Message>()
fun Any?.asRunnable() = asType<Runnable>()
fun Any?.asLiveWork() = asType<LiveWork>()
fun Any?.asJob() = asType<Job>()
fun Any?.asWork() = asType<Work>()

private typealias JobKFunction = KFunction<Job>
private typealias JobKProperty = KMutableProperty<Job?>
private typealias ResolverKClass = KClass<out Resolver>
private typealias ResolverKProperty = KMutableProperty<out Resolver?>
private typealias UnitKFunction = KFunction<Unit>

typealias AnyKClass = KClass<*>
typealias AnyKCallable = KCallable<*>
typealias AnyKFunction = KFunction<*>
typealias AnyKProperty = KProperty<*>
typealias AnyKMutableProperty = KMutableProperty<*>

private operator fun AnyKCallable.plus(lock: AnyKCallable) = this
private fun <R> AnyKCallable.synchronize(block: () -> R) = synchronized(this, block)

private typealias ID = Short
sealed interface State {
    object Succeeded : Resolved
    object Failed : Resolved

    interface Resolved : State {
        companion object : Resolved }
    interface Unresolved : State {
        companion object : Unresolved }
    interface Ambiguous : State {
        companion object : Ambiguous }

    companion object {
        operator fun invoke(): State = Lock.Open
        fun of(vararg args: Any?): State = Ambiguous
        operator fun get(id: ID): State = when (id.toInt()) {
            1 -> resolvedUnless { db === null || session === null }
            2 -> resolvedUnless { logDb === null || netDb === null }
            else ->
                Lock.Open
        }
        operator fun set(id: ID, lock: Any) { when (id.toInt()) {
            1 -> if (lock is Resolved) Scheduler.windDownClock()
        } }

        private fun resolvedUnless(predicate: Predicate) =
            if (predicate()) Unresolved
            else Resolved

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
    operator fun get(id: ID) = this
    operator fun set(id: ID, state: Any) {}
    operator fun inc() = this
    operator fun dec() = this
    operator fun plus(state: Any): State {
        when {
            this === State[2] && state is Ambiguous ->
                if (State[2] !is Resolved)
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

val SVC_TAG get() = if (onMainThread()) "SERVICE" else "CLOCK"