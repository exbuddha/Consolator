package backgammon.module

import android.content.*
import android.os.*
import androidx.lifecycle.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.*
import backgammon.module.BaseService.*
import backgammon.module.BaseActivity.*
import backgammon.module.Scheduler.Lock

inline fun <reified R : Resolver, T> Context.defer(member: KCallable<T>) =
    Scheduler.defer(member, R::class, this)
inline fun <reified R : Resolver> Context.defer(member: KFunction<Unit>, vararg value: Any?, noinline `super`: Work) =
    Scheduler.defer(member, R::class, this, *value, `super`)
inline fun <reified R : Resolver> Context.work(vararg id: Any?, noinline work: Work) =
    Scheduler.from(this, R::class, *id, work = work)
inline fun <reified R : Resolver> Context.step(vararg id: Any?, noinline step: CoroutineStep? = null) =
    work<R>(*id) { Scheduler.step(this, R::class, *id, step = step) }

interface SchedulerScope : CoroutineScope {
    override val coroutineContext
        get() = Scheduler
}

object Scheduler : MutableLiveData<Step?>(), SchedulerScope, CoroutineContext, StepObserver {
    inline fun <reified R : Resolver, T> defer(member: KCallable<T>, resolver: KClass<out R>?, vararg value: Any?): Unit? =
        when (resolver) {
            null -> null
            else -> when (member.javaClass.enclosingClass) {
                BaseService::class.java -> when (resolver) {
                    StartCommandResolver::class ->
                        setResolverThenCommit(
                            ::serviceOnStartCommandResolver,
                            WorkResolver::class)
                    BindResolver::class ->
                        setResolverThenCommit(
                            ::serviceOnBindResolver,
                            WorkResolver::class)
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
    fun from(vararg id: Any?, work: Work) {
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
    fun setResolverThenCommit(instance: ResolverKProperty, type: ResolverKClass) =
        (instance.reconstruct(type, null) as? WorkRef)?.commit()
    fun setResolverThenResolve(instance: ResolverKProperty, type: ResolverKClass, vararg value: Any?) =
        (instance.reconstruct(type, null) as? Resolver)?.resolve(value)

    open class Clock(
        name: String? = null,
        priority: Int = Thread.currentThread().priority
    ) : HandlerThread(name, priority), UniqueContext {
        override var startTime = 0L
        override fun start() {
            commitAsyncIfNotAlive {
                startTime = now()
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

    class Sequencer {
        fun ioStart(step: SequencerStep) {
            io(step)
            start()
        }
        fun ioResume(step: SequencerStep) {
            io(step)
            resume()
        }
        fun io(step: SequencerStep) = attach(Dispatchers.IO, step)
        fun io(index: Int, step: SequencerStep) = attach(index, Dispatchers.IO, step)
        fun ioAfter(step: SequencerStep) = attachAfter(Dispatchers.IO, step)
        fun ioBefore(step: SequencerStep) = attachBefore(Dispatchers.IO, step)
        fun unconfinedStart(step: SequencerStep) {
            unconfined(step)
            start()
        }
        fun unconfinedResume(step: SequencerStep) {
            unconfined(step)
            resume()
        }
        fun unconfined(step: SequencerStep) = attach(Dispatchers.Unconfined, step)
        fun unconfined(index: Int, step: SequencerStep) = attach(index, Dispatchers.Unconfined, step)
        fun unconfinedAfter(step: SequencerStep) = attachAfter(Dispatchers.Unconfined, step)
        fun unconfinedBefore(step: SequencerStep) = attachBefore(Dispatchers.Unconfined, step)

        private var observer: StepObserver = Scheduler
        private var seq: MutableList<LiveWork> = mutableListOf()
        private var ln: Int = -1
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
        private fun jump(index: Int = ++ln): Boolean? {
            return (index < seq.size && canJump()).also {
                if (it) ln = index
            }
        }
        private fun canJump() = !isObserving
        private fun advance() {
            prepare()
            while (jump() ?: return) {
                latestStep = seq[ln].first.invoke().also {
                    if (it === null)
                        bypass()
                    else
                        run(it) && return
                }
            }
            end()
        }
        private fun run(step: LiveStep): Boolean {
            step.observeForever(observer)
            isObserving = true
            return true
        }
        private fun bypass(step: Step? = null) {
            latestCapture = seq[ln].second?.invoke(step)
        }
        fun reset(step: LiveStep? = latestStep) {
            step?.removeObserver(observer)
            isObserving = false
        }
        fun end() {
            isCompleted = true
        }

        var isActive: Boolean = false
        var isObserving: Boolean = false
        var isCompleted: Boolean = false
        var isCancelled: Boolean = false
        var hasError: Boolean = false
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
        fun attach(step: SequencerStep) =
            stepToNull { liveData(block = step) }.also { attach(it) }
        fun attach(step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(block = step) } to capture).also { attach(it) }
        fun attach(context: CoroutineContext, step: SequencerStep) =
            stepToNull { liveData(context, block = step) }.also { attach(it) }
        fun attach(context: CoroutineContext, step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(context, block = step) } to capture).also { attach(it) }
        fun attach(index: Int, step: SequencerStep) =
            stepToNull { liveData(block = step) }.also { attach(index, it) }
        fun attach(index: Int, step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(block = step) } to capture).also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, step: SequencerStep) =
            stepToNull { liveData(context, block = step) }.also { attach(index, it) }
        fun attach(index: Int, context: CoroutineContext, step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(context, block = step) } to capture).also { attach(index, it) }
        fun attachAfter(step: SequencerStep) =
            stepToNull { liveData(block = step) }.also { attachAfter(it) }
        fun attachAfter(step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(block = step) } to capture).also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, step: SequencerStep) =
            stepToNull { liveData(context, block = step) }.also { attachAfter(it) }
        fun attachAfter(context: CoroutineContext, step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(context, block = step) } to capture).also { attachAfter(it) }
        fun attachBefore(step: SequencerStep) =
            stepToNull { liveData(block = step) }.also { attachBefore(it) }
        fun attachBefore(step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(block = step) } to capture).also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, step: SequencerStep) =
            stepToNull { liveData(context, block = step) }.also { attachBefore(it) }
        fun attachBefore(context: CoroutineContext, step: SequencerStep, capture: CaptureFunction) =
            ({ liveData(context, block = step) } to capture).also { attachBefore(it) }

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
        private fun nullStepTo(block: CaptureFunction) = nullStep to block
        private fun stepToNull(step: () -> LiveStep?) = step to nullBlock
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
        private inline fun MutableList<LiveWork>.noneReversed(predicate: LiveWorkPredicate): Boolean {
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

    var clock: Clock? = null
    var sequencer: Sequencer? = null

    fun clearResolverObjects() {
        serviceOnStartCommandResolver = null
        serviceOnBindResolver = null
        activityConfigurationChangeManager = null
        activityNightModeChangeManager = null
        activityLocalesChangeManager = null
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
    private fun trySafelyForAnnotatedScope(step: CoroutineStep?) =
        trySafelyForResult { annotatedScope(step) }
    private fun annotatedScope(step: CoroutineStep?) =
        (step as KCallable<*>).schedulerScope!!.type.reconstruct(step)

    enum class Lock : State { Closed, Open }

    @Retention(SOURCE)
    @Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    annotation class Event(val transit: Short = 0)
    private val KCallable<*>.event
        get() = annotations.find { it is Event } as? Event
    fun trySafelyForAnnotatedEvent(step: KFunction<*>) =
        trySafelyForResult { annotatedEvent(step) }
    fun annotatedEvent(step: KFunction<*>) =
        step.event!!

    override fun onChanged(step: Step?) {
        if (step !== null) runBlocking { step() }
    }

    object EventBus : Flow<Step?> {
        override suspend fun collect(collector: FlowCollector<Step?>) {
            collector.emitAll(this)
        }
    }
}

val Step.transit
    get() = Scheduler.trySafelyForAnnotatedEvent(this as KFunction<*>)?.transit

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
    lifecycleScope.launch(context, start, step)
fun LifecycleOwner.launch(context: CoroutineContext, step: CoroutineStep) =
    lifecycleScope.launch(context, block = step)
fun LifecycleOwner.launch(step: CoroutineStep) =
    launch(Scheduler, step)
fun LifecycleOwner.relaunchJobIfNotActive(
    instance: KMutableProperty<Job?>,
    context: CoroutineContext = Scheduler,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: CoroutineStep) =
    if (instance.getter.call()?.isActive == true) instance as Job
    else launch(context, start, block).also { instance.setter.call(it) }

val mainThread = Thread.currentThread()
fun Thread.isMainThread() = this === mainThread
fun onMainThread() = Thread.currentThread().isMainThread()
private fun withPriority(priority: Int, target: Runnable) = Thread(target).also { it.priority = priority }

abstract class WorkRef {
    var work: Work? = null
    var id: AnyArray? = null
    open fun commit(): Unit? {
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
    asMutableProperty().expire()
}
abstract class StepResolver : StepRef(), Resolver
abstract class WorkResolver : WorkRef(), Resolver
abstract class ForgetfulWorkResolver : WorkRef(), Resolver {
    override fun commit() = super.commit().also { work = null }
}

typealias JobFunction = suspend (Any?) -> Any?

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTreeRoot

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

private typealias SequencerScope = LiveDataScope<Step?>
suspend fun SequencerScope.reset() { Scheduler.sequencer?.apply { emit { reset() } } }
private typealias SequencerStep = suspend SequencerScope.() -> Unit
private typealias StepObserver = Observer<Step?>
private typealias LiveStep = LiveData<Step?>
private typealias CaptureFunction = (Step?) -> Any?
private typealias LiveWork = Pair<() -> LiveStep?, CaptureFunction?>
private typealias LiveWorkPredicate = (LiveWork) -> Boolean

interface Expiry : MutableSet<Lifetime> {
    fun unsetAll(property: KMutableProperty<*>) {
        forEach { alive ->
            if (alive(property) == false)
                property.expire()
        }
    }
    companion object : Expiry {
        override fun add(element: Lifetime): Boolean = TODO()
        override fun addAll(elements: Collection<Lifetime>): Boolean = TODO()
        override fun clear() = TODO()
        override fun iterator(): MutableIterator<Lifetime> = TODO()
        override fun remove(element: Lifetime): Boolean = TODO()
        override fun removeAll(elements: Collection<Lifetime>): Boolean = TODO()
        override fun retainAll(elements: Collection<Lifetime>): Boolean = TODO()
        override val size: Int
            get() = TODO()
        override fun contains(element: Lifetime): Boolean = TODO()
        override fun containsAll(elements: Collection<Lifetime>): Boolean = TODO()
        override fun isEmpty(): Boolean = TODO()
    }
}
typealias Lifetime = (KMutableProperty<*>) -> Boolean?
private fun KMutableProperty<*>.expire() = setter.call(null)
private fun Any?.asMutableProperty() = this as KMutableProperty<*>

private typealias RunnableList = MutableList<Runnable>
private typealias MessageFunction = (Message) -> Any?
typealias Work = () -> Unit
typealias Step = suspend () -> Unit
typealias CoroutineStep = suspend CoroutineScope.() -> Unit

private typealias ResolverKClass = KClass<out Resolver>
private typealias ResolverKProperty = KMutableProperty<out Resolver?>

private typealias ID = Short
sealed interface State {
    object Finished : State
    companion object {
        operator fun invoke(): State = Lock.Open
        operator fun get(id: ID): State = Lock.Open
        operator fun set(id: ID, lock: Any) {}
        operator fun plus(lock: Any): State = TODO()
        operator fun plusAssign(lock: Any) {}
        operator fun minus(lock: Any): State = TODO()
        operator fun minusAssign(lock: Any) {}
        operator fun times(lock: Any): State = TODO()
        operator fun timesAssign(lock: Any) {}
        operator fun div(lock: Any): State = TODO()
        operator fun divAssign(lock: Any) {}
        operator fun rem(lock: Any): State = TODO()
        operator fun remAssign(lock: Any) {}
        operator fun unaryPlus(): State = TODO()
        operator fun unaryMinus(): State = TODO()
        operator fun rangeTo(lock: Any): State = TODO()
        operator fun not(): State = TODO()
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