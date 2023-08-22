package backgammon.module

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
import kotlinx.coroutines.flow.*
import backgammon.module.BaseService.*
import backgammon.module.BaseActivity.*
import backgammon.module.Scheduler.EventBus.Relay
import backgammon.module.Scheduler.Lock
import backgammon.module.application.*

inline fun <reified R : Deferral, T> Context.defer(member: KCallable<T>) =
    Scheduler.defer(member, R::class, this)
inline fun <reified R : Deferral> Context.defer(member: KFunction<Unit>, vararg value: Any?, noinline `super`: Work) =
    Scheduler.defer(member, R::class, this, *value, `super`)
inline fun <reified R : Deferral> Context.work(vararg id: Any?, noinline work: Work) =
    Scheduler.work(this, R::class, *id, work = work)
inline fun <reified R : Deferral> Context.step(vararg id: Any?, noinline step: CoroutineStep? = null) =
    work<R>(*id) { Scheduler.step(this, R::class, *id, step = step) }

interface SchedulerScope : CoroutineScope {
    override val coroutineContext
        get() = Scheduler
}

object Scheduler : MutableLiveData<Step?>(), SchedulerScope, CoroutineContext, StepObserver {
    inline fun <reified R : Deferral, T> defer(member: KCallable<T>, resolver: KClass<out R>?, vararg value: Any?): Unit? =
        when (resolver) {
            Migration::class ->
                setResolverThenCommit(
                    ::applicationMigrationResolver,
                    Migration::class)
            null -> null
            else -> when (member.javaClass.enclosingClass) {
                BaseService::class.java -> when (resolver) {
                    StartCommandResolver::class ->
                        setResolverThenCommit(
                            ::serviceOnStartCommandResolver,
                            StartCommandResolver::class)
                    BindResolver::class ->
                        setResolverThenCommit(
                            ::serviceOnBindResolver,
                            BindResolver::class)
                    else ->
                        throw BaseImplementationRestriction
                }
                BaseActivity::class.java -> when (resolver) {
                    ConfigurationChangeManager::class ->
                        setResolverThenResolve(
                            ::activityConfigurationChangeManager,
                            ConfigurationChangeManager::class,
                            value)
                    NightModeChangeManager::class ->
                        setResolverThenResolve(
                            ::activityNightModeChangeManager,
                            NightModeChangeManager::class,
                            value)
                    LocalesChangeManager::class ->
                        setResolverThenResolve(
                            ::activityLocalesChangeManager,
                            LocalesChangeManager::class,
                            value)
                    else ->
                        throw BaseImplementationRestriction
                }
                else -> throw BaseImplementationRestriction
            }
        }
    fun work(vararg id: Any?, work: Work) {
        when (id[0]) {
            is BaseServiceScope -> when (id[1]) {
                StartCommandResolver::class ->
                    serviceOnStartCommandResolver!!.assignWork(work, id)
                BindResolver::class ->
                    serviceOnBindResolver!!.assignWork(work, id)
                else ->
                    throw BaseImplementationRestriction
            }
            is BaseActivity -> when (id[1]) {
                ConfigurationChangeManager::class ->
                    activityConfigurationChangeManager!!.assignWork(work, id)
                NightModeChangeManager::class ->
                    activityNightModeChangeManager!!.assignWork(work, id)
                LocalesChangeManager::class ->
                    activityLocalesChangeManager!!.assignWork(work, id)
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
                                trySafelyForAnnotatedScope(step) ?:
                                scope)
                        }
                    return
                }
                is BaseActivity -> {
                    when (context[1]) {
                        ConfigurationChangeManager::class ->
                            activityConfigurationChangeManager!!.assignStepThenResolve(step)
                        NightModeChangeManager::class ->
                            activityNightModeChangeManager!!.assignStepThenResolveAndUnset(step)
                        LocalesChangeManager::class ->
                            activityLocalesChangeManager!!.assignStepThenResolveAndUnset(step)
                        else ->
                            throw BaseImplementationRestriction
                    }
                    return
                }
            }
        else if (step !== null) {
            clock {
                annotatedScope(step).step()
            }
            return
        }
        throw BaseImplementationRestriction
    }
    fun service(step: CoroutineStep) = runBlocking {
        step(
            trySafelyForAnnotatedScope(step) ?:
            service!!)
    }

    var serviceOnStartCommandResolver: StartCommandResolver? = null
        private set
    var serviceOnBindResolver: BindResolver? = null
        private set
    var activityConfigurationChangeManager: ConfigurationChangeManager? = null
        private set
    var activityNightModeChangeManager: NightModeChangeManager? = null
        private set
    var activityLocalesChangeManager: LocalesChangeManager? = null
        private set
    var applicationMigrationResolver: Migration? = null
        private set
    fun setResolverThenCommit(instance: ResolverKProperty, type: ResolverKClass) =
        (instance.reconstruct(type, relay = null) as? WorkRef)?.commit()
    fun setResolverThenResolve(instance: ResolverKProperty, type: ResolverKClass, vararg value: Any?) =
        (instance.reconstruct(type, value[0], null) as? Resolver)?.resolve(value)

    open class Clock(
        name: String? = null,
        priority: Int = Thread.currentThread().priority
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

        private var handler: Handler? = null
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
    }

    class Sequencer(private var observer: StepObserver) {
        fun ioStart(step: SequencerStep) {
            io(false, step)
            start()
        }
        fun ioStartResettingFirstly(step: SequencerStep) {
            io(false, resettingFirstly(step))
            start()
        }
        fun ioStartResettingLastly(step: SequencerStep) {
            io(false, resettingLastly(step))
            start()
        }
        fun ioResume(async: Boolean = false, step: SequencerStep) {
            io(async, step)
            resume()
        }
        fun ioResumeResettingFirstly(async: Boolean = false, step: SequencerStep) {
            io(async, resettingFirstly(step))
            resume()
        }
        fun ioResumeResettingLastly(async: Boolean = false, step: SequencerStep) {
            io(async, resettingLastly(step))
            resume()
        }
        fun io(async: Boolean = false, step: SequencerStep) = attach(Dispatchers.IO, async, step)
        fun io(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Dispatchers.IO, async, step)
        fun ioAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Dispatchers.IO, async, step)
        fun ioBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Dispatchers.IO, async, step)
        fun unconfinedStart(step: SequencerStep) {
            unconfined(false, step)
            start()
        }
        fun unconfinedStartResettingFirstly(step: SequencerStep) {
            unconfined(false, resettingFirstly(step))
            start()
        }
        fun unconfinedStartResettingLastly(step: SequencerStep) {
            unconfined(false, resettingLastly(step))
            start()
        }
        fun unconfinedResume(async: Boolean = false, step: SequencerStep) {
            unconfined(async, step)
            resume()
        }
        fun unconfinedResumeResettingFirstly(async: Boolean = false, step: SequencerStep) {
            unconfined(async, resettingFirstly(step))
            resume()
        }
        fun unconfinedResumeResettingLastly(async: Boolean = false, step: SequencerStep) {
            unconfined(async, resettingLastly(step))
            resume()
        }
        fun unconfined(async: Boolean = false, step: SequencerStep) = attach(Dispatchers.Unconfined, async, step)
        fun unconfined(index: Int, async: Boolean = false, step: SequencerStep) = attach(index, Dispatchers.Unconfined, async, step)
        fun unconfinedAfter(async: Boolean = false, step: SequencerStep) = attachAfter(Dispatchers.Unconfined, async, step)
        fun unconfinedBefore(async: Boolean = false, step: SequencerStep) = attachBefore(Dispatchers.Unconfined, async, step)

        constructor() : this(Scheduler)
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
            while (jump() ?: return) {
                (work.run(::observe) ?:
                capture()) || return
            }
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
        private fun capture(): Boolean {
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

        fun attach(work: LiveWork) {
            seq.add(work)
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
                add(index, work)
            }
        }
        fun attachOnce(index: Int, work: LiveWork) {
            if (work.isNotAttached(index))
                seq.add(index, work)
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
            stepToNull(async) { liveData(block = step) }.also { attach(it) }
        fun attach(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = step) }, capture, async).also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = step) }.also { attach(it) }
        fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = step) }, capture, async).also { attach(it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = step) }.also { attach(index, it) }
        fun attach(index: Int, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = step) }, capture, async).also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = step) }.also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = step) }, capture, async).also { attach(index, it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = step) }.also { attachAfter(it) }
        fun attachAfter(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = step) }, capture, async).also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = step) }.also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = step) }, capture, async).also { attachAfter(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(block = step) }.also { attachBefore(it) }
        fun attachBefore(async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(block = step) }, capture, async).also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
            stepToNull(async) { liveData(context, block = step) }.also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
            Triple({ liveData(context, block = step) }, capture, async).also { attachBefore(it) }

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
        private fun stepToNull(async: Boolean = false, step: () -> LiveStep?) = Triple(step, nullBlock, async)
        private val nullStep: () -> LiveStep? = { null }
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
    var sequencer: Sequencer? = null

    fun windDown() {
        ignore()
        clock?.apply {
            Process.setThreadPriority(threadId, Thread.NORM_PRIORITY)
        }
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
    annotation class Scope(val type: KClass<out CoroutineScope>)
    private val KCallable<*>.schedulerScope
        get() = annotations.find { it is Scope } as? Scope
    fun trySafelyForAnnotatedScope(step: CoroutineStep?) =
        trySafelyForResult { annotatedScope(step) }
    private fun annotatedScope(step: CoroutineStep?) =
        (step as KCallable<*>).schedulerScope!!.type.reconstruct(step)

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class Event(val transit: Short = 0) {
        @Retention(SOURCE)
        @Target(CONSTRUCTOR, FUNCTION, PROPERTY, EXPRESSION)
        annotation class Listening(val timeout: Long = 0)
    }
    private val KCallable<*>.event
        get() = annotations.find { it is Event } as? Event
    fun trySafelyForAnnotatedEvent(step: KFunction<*>) =
        trySafelyForResult { annotatedEvent(step) }
    fun annotatedEvent(step: KFunction<*>) =
        step.event!!

    private lateinit var _key: CoroutineContext.Key<*>
    private val _element by lazy {
        object : CoroutineContext.Element {
            override val key
                get() = _key
        }
    }
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

    @OptIn(FlowPreview::class)
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
}

val Step.transit
    get() = if (this is Relay) transit else annotatedEvent?.transit
val ContextStep.transit
    get() = annotatedEvent?.transit
private val Any.annotatedEvent
    get() = Scheduler.trySafelyForAnnotatedEvent(this as KFunction<*>)

inline fun <R> scheduler(block: Scheduler.() -> R) = Scheduler.block()
fun scheduleNow(step: Step) { Scheduler.value = step }
fun schedule(step: Step) = Scheduler.postValue(step)
fun Context.scheduleNow(ref: ContextStep) = scheduleNow(step = { ref() })
fun Context.schedule(ref: ContextStep) = schedule(step = { ref() })

fun clock(callback: Runnable) = Scheduler.clock!!.post(callback)
fun clockAhead(callback: Runnable) = Scheduler.clock!!.postAhead(callback)
fun <T> clock(step: suspend CoroutineScope.() -> T) = clock(blockingRunnableOf(step))
fun <T> clockAhead(step: suspend CoroutineScope.() -> T) = clockAhead(blockingRunnableOf(step))
fun <T> blockingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { runBlocking(block = step) }
inline fun <R> commitAsync(lock: Any, crossinline predicate: Predicate, crossinline block: () -> R) {
    if (predicate())
        synchronized(lock) {
            if (predicate()) block()
        }
}

fun LifecycleOwner.launch(context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    (Scheduler.trySafelyForAnnotatedScope(step) ?:
    lifecycleScope).launch(context, start, step)
fun LifecycleOwner.launch(context: CoroutineContext, step: CoroutineStep) =
    (Scheduler.trySafelyForAnnotatedScope(step) ?:
    lifecycleScope).launch(context, block = step)
fun LifecycleOwner.launch(step: CoroutineStep) =
    launch(Scheduler, step)
fun LifecycleOwner.relaunchJobIfNotActive(
    instance: KMutableProperty<Job?>,
    context: CoroutineContext = Scheduler,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: CoroutineStep) =
    if (instance.getter.call()?.isActive == true) instance as Job
    else launch(context, start, block).also { instance.setter.call(it) }
fun LifecycleOwner.close(node: KClass<out Annotation>) {}
infix fun Job.onCancel(action: DescriptiveStep) {}

val mainThread = Thread.currentThread()
fun Thread.isMainThread() = this === mainThread
fun onMainThread() = Thread.currentThread().isMainThread()
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
interface Resolver : CoroutineScope {
    fun resolve(vararg id: Any?): Unit =
        throw BaseImplementationRestriction
    override val coroutineContext
        get() = Scheduler
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
private fun Resolver.assignStepThenResolveAndUnset(step: CoroutineStep?) {
    assignStepThenResolve(step)
    asMutableProperty()?.expire()
}
abstract class WorkResolver : WorkRef(), Resolver
abstract class ForgetfulWorkResolver : WorkRef(), Resolver {
    override fun commit() {
        super.commit()
        work = null
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

typealias JobFunction = suspend (Any?) -> Any?
typealias CoroutineStep = suspend CoroutineScope.() -> Unit
private typealias DescriptiveStep = suspend SchedulerScope.(Job) -> Unit
private typealias SequencerScope = LiveDataScope<Step?>
suspend fun SequencerScope.reset() { emit { reset() } }
private typealias SequencerStep = suspend SequencerScope.() -> Unit
private typealias StepObserver = Observer<Step?>
private typealias LiveStep = LiveData<Step?>
private typealias CaptureFunction = AnyFunction
private typealias LiveWork = Triple<() -> LiveStep?, CaptureFunction?, Boolean>
private typealias LiveSequence = MutableList<LiveWork>
private typealias LiveWorkPredicate = (LiveWork) -> Boolean

interface Expiry : MutableSet<Lifetime> {
    fun unsetAll(property: KMutableProperty<*>) {
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
private fun Any?.asMutableProperty() = this as? KMutableProperty<*>

private typealias RunnableList = MutableList<Runnable>
private typealias MessageFunction = (Message) -> Any?
typealias Work = () -> Unit
typealias Step = suspend () -> Unit

private typealias ResolverKClass = KClass<out Deferral>
private typealias ResolverKProperty = KMutableProperty<out Deferral?>

private typealias ID = Short
sealed interface State {
    object Failed : Resolved
    object Succeeded : Resolved
    interface Resolved : State {
        companion object : Resolved
    }
    interface Ambiguous : State {
        companion object : Ambiguous
    }
    companion object {
        operator fun invoke(): State = Lock.Open
        operator fun get(id: ID): State = Lock.Open
        operator fun set(id: ID, lock: Any) {
            when (id.toInt()) {
                1 -> if (lock is Resolved) Scheduler.windDown()
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
    operator fun plus(state: Any) = this
    operator fun plusAssign(state: Any) {}
    operator fun minus(state: Any) = this
    operator fun minusAssign(state: Any) {}
    operator fun times(state: Any) = this
    operator fun timesAssign(state: Any) {}
    operator fun div(state: Any) = this
    operator fun divAssign(state: Any) {}
    operator fun rem(state: Any) = this
    operator fun remAssign(state: Any) {}
    operator fun unaryPlus() = this
    operator fun unaryMinus() = this
    operator fun rangeTo(state: Any) = this
    operator fun not(): State = this
    operator fun contains(state: Any) = state === this
    operator fun compareTo(state: Any) = 0
}