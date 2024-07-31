package iso.consolator

import android.app.*
import android.content.*
import android.os.*
import androidx.core.content.ContextCompat.registerReceiver
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.room.*
import ctx.consolator.*
import data.consolator.*
import java.io.*
import java.lang.*
import java.util.LinkedList
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import iso.consolator.Scheduler.defer
import iso.consolator.State.*
import iso.consolator.activity.*
import iso.consolator.application.*
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO

internal fun commit(step: CoroutineStep) =
    (service ?:
    step.annotatedScope ?:
    foregroundLifecycleOwner?.lifecycleScope ?:
    SchedulerScope()).let { scope ->
        (scope::class.memberFunctions.find {
            it.name == "commit" &&
            it.parameters.size == 2 &&
            it.parameters[1].name == "step" }
        ?: Scheduler::commit).call(scope, step) }

fun commit(vararg context: Any?): Any? =
    when (val task = context.firstOrNull()) {
        (task === START) -> {
            SchedulerScope {
                init()
                preferClock()
                preferScheduler() }
            if (SchedulerScope.isClockPreferred)
                clock = Clock(SVC, Thread.MAX_PRIORITY)
                @Synchronous @Tag(CLOCK_INIT) {
                    // turn clock until scope is active
                    log(info, SVC_TAG, "Clock is detected.") }
                .alsoStart()
            with(foregroundContext) {
                startService(
                intendFor(context.secondOrNull().asType<Service>()!!::class)
                .putExtra(START_TIME_KEY,
                    startTime()))
        } }
        else -> Unit }

interface BaseServiceScope : ResolverScope, ReferredContext, UniqueContext {
    fun Intent.invoke(flags: Int, startId: Int, mode: Int): Int? {
        if (SchedulerScope.isClockPreferred)
            startClockSafely(this@invoke)
        if (State[2] !is Resolved)
            commit @Synchronous @Tag(SERVICE_INIT) {
                setStartTime(this@invoke)
                Sequencer {
                if (isLogDbNull)
                    attach(IO, true)
                    @Tag(STAGE_BUILD_LOG_DB) { self ->
                    coordinateBuildDatabase(self,
                        ::logDb,
                        stage = Context::stageLogDbCreated) }
                if (isNetDbNull)
                    attach(IO, true)
                    @Tag(STAGE_BUILD_NET_DB) { self ->
                    coordinateBuildDatabase(self,
                        ::netDb,
                        step = arrayOf(@Tag(STAGE_INIT_NET_DB) suspend {
                            updateNetworkCapabilities()
                            updateNetworkState() }),
                        stage = Context::stageNetDbInitialized) }
                resumeAsync()
    } }
    return mode }

    private fun startClockSafely(intent: Intent?) =
        intent?.run {
        if (hasCategory(START_TIME_KEY))
            Clock.startSafely() }

    private fun setStartTime(intent: Intent?) =
        trySafelyForResult {
        intent?.getLongExtra(
            START_TIME_KEY,
            foregroundContext.asUniqueContext()?.startTime
            ?: now()) }
        ?.apply(::startTime::set)

    private suspend inline fun <reified D : RoomDatabase> SequencerScope.coordinateBuildDatabase(identifier: Any?, instance: KMutableProperty<out D?>, noinline stage: ContextStep?) =
        buildDatabaseOrResetByTag(identifier, instance, stage, synchronize(identifier, stage))

    private suspend inline fun <reified D : RoomDatabase> SequencerScope.coordinateBuildDatabase(identifier: Any?, instance: KMutableProperty<out D?>, vararg step: AnyStep, noinline stage: ContextStep?) =
        buildDatabaseOrResetByTag(identifier, instance, stage, synchronize(identifier, *step, stage = stage))

    private suspend inline fun <reified D : RoomDatabase> SequencerScope.buildDatabaseOrResetByTag(identifier: Any?, instance: KMutableProperty<out D?>, noinline stage: ContextStep?, noinline post: AnyStep) =
        buildDatabaseOrResetByTag(identifier, instance, stage, post, ::whenNotNullOrResetByTag)

    private suspend inline fun <reified D : RoomDatabase> SequencerScope.buildDatabaseOrResetByTag(identifier: Any?, instance: KMutableProperty<out D?>, noinline stage: ContextStep?, noinline post: AnyStep, condition: PropertyCondition) =
        returnItsTag(identifier)?.let { tag ->
        buildDatabaseOrResetByTag(instance, tag)
        condition(instance, tag,
            formAfterMarkingTagsForCtxReform(tag, stage, post, currentJob())) }

    private suspend inline fun <reified D : RoomDatabase> SequencerScope.buildDatabaseOrResetByTag(instance: KMutableProperty<out D?>, tag: String) {
        ref?.get()?.run<Context, D?> {
            sequencer { trySafelyCanceling {
            resetByTagOnError(tag) {
            commitAsyncAndResetByTag(instance, tag, ::buildDatabase) } } }
        }?.apply(instance::set) }

    private suspend inline fun <R> SequencerScope.commitAsyncAndResetByTag(lock: AnyKProperty, tag: String, block: () -> R) =
        commitAsyncOrResetByTag(lock, tag) { block().also { resetByTag(tag) } }

    private suspend inline fun <R> SequencerScope.commitAsyncOrResetByTag(lock: AnyKProperty, tag: String, block: () -> R) =
        commitAsyncForResult(lock, lock::isNull, block) { resetByTag(tag); null }

    private fun SequencerScope.synchronize(identifier: Any?, stage: ContextStep?) =
        if (stage !== null) form(stage)
        else ignore

    private fun SequencerScope.synchronize(identifier: Any?, vararg step: AnyStep, stage: ContextStep?) =
        if (stage !== null) form(stage, *step)!!
        else ignore

    private val ignore get() = @Tag(IGNORE) emptyStep

    private fun SequencerScope.form(stage: ContextStep) = suspend { change(stage) }

    private fun SequencerScope.form(stage: ContextStep, vararg step: AnyStep) = step.first() then form(stage)

    private fun formAfterMarkingTagsForCtxReform(tag: String, stage: ContextStep?, form: AnyStep, job: Job) =
        (form after { markTagsForCtxReform(tag, stage, form, job) })!!

    private suspend inline fun whenNotNullOrResetByTag(instance: AnyKProperty, stage: String, step: AnyStep) =
        if (instance.isNotNull())
            step()
        else resetByTag(stage)

    override fun commit(step: AnyCoroutineStep) =
        step.markTagForSvcCommit().let { step ->
        SchedulerScope.DEFAULT_OPERATOR?.invoke(step)
        ?: attach(step, { launch { step() } }) }
}

@Coordinate
object Scheduler : SchedulerScope, MutableLiveData<AnyStep?>(), AnyStepObserver, Synchronizer<AnyStep>, PriorityQueue<AnyFunction> {
    private fun observe() {
        observeForever(this)
        SchedulerScope.isSchedulerObserved = true }

    internal fun observeAsync() = commitAsync(this, hasObservers()::not, ::observe)

    internal fun observe(owner: LifecycleOwner) = observe(owner, this)

    internal fun ignore() {
        removeObserver(this)
        SchedulerScope.isSchedulerObserved = false }

    fun <T : Resolver> defer(resolver: KClass<out T>, provider: Any, vararg context: Any?): Unit? {
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
    internal var applicationMigrationManager: MigrationManager? = null
    private var applicationMemoryManager: MemoryManager? = null

    internal fun clearResolverObjects() {
        activityConfigurationChangeManager = null
        activityNightModeChangeManager = null
        activityLocalesChangeManager = null
        applicationMigrationManager = null
        applicationMemoryManager = null }

    override val coroutineContext
        get() = Default

    override fun commit(step: AnyCoroutineStep) =
        attach(step.markTagForSchCommit(), ::launch)

    override fun onChanged(value: AnyStep?) {
        value.markTagForSchExec()
        ?.run { synchronize(this, ::block) } }

    override fun <R> synchronize(lock: AnyStep?, block: () -> R) =
        if (lock !== null)
            if (lock.isScheduledAhead) {
                with(queue) { map {
                    remove(it)
                    it() } }
                block() }
            else {
                queue.add(block)
                Unit.type() }
        else Unit.type()

    override var queue: MutableList<AnyFunction> = mutableListOf()

    internal operator fun <R> invoke(work: Scheduler.() -> R) = this.work()
}

sealed interface SchedulerScope : ResolverScope {
    companion object {
        internal fun init() {
            invoke().asType<Scheduler>()
            ?.observeAsync() }

        fun preferClock() {
            DEFAULT_RESOLVER = HandlerScope }

        fun preferScheduler(callback: AnyFunction? = null) {
            if (DEFAULT_RESOLVER === null)
                DEFAULT_RESOLVER = Scheduler
            if (!isSchedulerObserved)
                processLifecycleScope.launch {
                    Scheduler.observeAsync()
                    callback?.invoke() } }

        internal fun avoidClock() {
            if (isClockPreferred)
                DEFAULT_RESOLVER = null }

        internal fun avoidScheduler() {
            if (isSchedulerPreferred)
                preferClock() }

        internal val isClockPreferred
            get() = DEFAULT_RESOLVER === HandlerScope

        internal val isSchedulerPreferred
            get() = DEFAULT_RESOLVER !== HandlerScope

        internal var isSchedulerObserved = false

        private var DEFAULT_RESOLVER: ResolverScope? = null
            set(value) {
                // engine-wide reconfiguration
                DEFAULT_OPERATOR =
                    if (value is HandlerScope)
                        ::handleAhead
                    else null
                field = value }

        internal var DEFAULT_OPERATOR: AnyCoroutineFunction? = null
            set(value) {
                // message queue reconfiguration
                field = value }

        operator fun invoke() = DEFAULT_RESOLVER ?: Scheduler

        internal operator fun <R> invoke(block: Companion.() -> R) = this.block()
    }
}

interface ResolverScope : CoroutineScope, Transactor<AnyCoroutineStep, Any?> {
    override val coroutineContext: CoroutineContext
        get() = SchedulerContext

    fun windDown() = Unit
}

interface Resolver : ResolverScope {
    override fun commit(step: AnyCoroutineStep) =
        commit(blockOf(step))

    fun commit(vararg context: Any?) =
        context.lastOrNull().asWork()?.invoke()
}

fun ResolverScope.commit(vararg tag: Tag) =
    commit(*tag.mapToStringArray())

fun ResolverScope.commit(vararg path: Path) =
    commit(*path.mapToStringArray())

inline fun <reified T : Resolver> LifecycleOwner.defer(member: UnitKFunction, vararg context: Any?) =
    defer(T::class, T::class, member, *context)

inline fun <reified T : Resolver> Context.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    defer(T::class, T::class, member, *context, implicit(`super`))

inline fun <reified T : Resolver> Activity.defer(member: UnitKFunction, vararg context: Any?, noinline `super`: Work) =
    defer(T::class, this, member, *context, `super`)

fun implicit(`super`: Work) = when {
    `super`.isImplicit -> {
        `super`()
        emptyWork }
    else -> `super` }

internal fun attach(step: AnyCoroutineStep, vararg args: Any?): Any? {
    val enlist: AnyCoroutineFunction? = (
        args.firstOrNull()
        ?: if (SchedulerScope.isClockPreferred)
            ::handle
        else CoroutineScope::launch)
        .asType()
    val transfer: AnyCoroutineFunction? = (
        args.secondOrNull()
        ?: ::reattach)
        .asType()
    return when (val result = trySafelyForResult { enlist?.invoke(step) }) {
        null, false ->
            transfer?.invoke(@Unlisted step)
        true, is Job ->
            result
        else -> if (!Clock.isRunning)
            transfer?.invoke(@Enlisted step)
        else
            result } }

private fun reattach(step: CoroutineStep, handler: CoroutineFunction = ::launch) =
    try {
        if (step.isEnlisted)
            trySafelyForResult { detach(step) }
            ?.run(handler)
        else handler(step) }
    catch (_: Throwable) {
        repost(step) }

private fun detach(step: CoroutineStep) =
    @Unlisted with(Clock) {
        ::isRunning.then {
        getRunnable(step)?.detach()
        ?: getMessage(step)?.detach()?.asRunnable() } }
    ?.asCoroutine()
    ?: step

private fun launch(it: CoroutineStep) =
    Scheduler.launch(SchedulerContext, block = it
        .markTagForSchLaunch()
        .afterTrackingTagsForJobLaunch())
    .apply { saveNewElement(it) }

private object SchedulerContext : CoroutineContext {
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R {
        // update continuation state
        return operation(initial, SchedulerElement) }

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        // notify element continuation
        return null }

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        // reimpose continuation rules
        return this }
}

private interface SchedulerElement : CoroutineContext.Element {
    companion object : SchedulerElement {
        override val key
            get() = SchedulerKey } }

private interface SchedulerKey : CoroutineContext.Key<SchedulerElement> {
    companion object : SchedulerKey }

internal fun CoroutineScope.relaunch(instance: JobKProperty, context: CoroutineContext = SchedulerContext, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)

internal fun LifecycleOwner.relaunch(instance: JobKProperty, context: CoroutineContext = SchedulerContext, start: CoroutineStart = CoroutineStart.DEFAULT, step: CoroutineStep) =
    relaunch(::launch, instance, context, start, step)

private fun relaunch(launcher: JobKFunction, instance: JobKProperty, context: CoroutineContext, start: CoroutineStart, step: CoroutineStep) =
    instance.require({ !it.isActive }) {
        launcher.call(context, start, step) }
    .also { instance.markTag() }

internal fun launch(context: CoroutineContext = SchedulerContext, start: CoroutineStart = CoroutineStart.DEFAULT, step: AnyCoroutineStep) =
    step.markTagForSchLaunch()
        .afterTrackingTagsForJobLaunch(context, start).let { step ->
    Scheduler.launch(context, start, step)
        .apply { saveNewElement(step) } }

fun LifecycleOwner.launch(context: CoroutineContext = SchedulerContext, start: CoroutineStart = CoroutineStart.DEFAULT, step: AnyCoroutineStep): Job? {
    val scope = step.annotatedScope ?: lifecycleScope
    val (context, start, step) = scope.determineCoroutine(context, start, step)
    step.markTagForFloLaunch()
        .afterTrackingTagsForJobLaunch(context, start).let { step ->
    return scope.launch(context, start, step)
        .apply { saveNewElement(step) } } }

private fun CoroutineScope.determineCoroutine(context: CoroutineContext, start: CoroutineStart, step: AnyCoroutineStep) =
    Triple(
        // context key <-> step
        if (context.isSchedulerContext()) context
        else SchedulerContext + context,
        start,
        step)

private fun CoroutineContext.isSchedulerContext() =
    this is SchedulerContext ||
    this[SchedulerKey] is SchedulerElement

private fun AnyCoroutineStep.afterTrackingTagsForJobLaunch(context: CoroutineContext? = null, start: CoroutineStart? = null) =
    (after { job, _ -> markTagsForJobLaunch(context, start, this, job) })!!

private fun Job.saveNewElement(step: AnyCoroutineStep) {}

private fun Job.attachToElement(next: AnyCoroutinePointer): AnyCoroutineStep = TODO()

private fun Job.markedCoroutineStep(): AnyCoroutineStep = TODO()

// from this point on, step and context are the same
// this is not the coroutine context but the context of the step for the job
// steps that are concurrent (by design) will be double-pointed for uniqueness

private fun AnyCoroutineStep.attachToContext(next: AnyCoroutineStep): CoroutineStep = TODO()

private fun AnyCoroutineStep.markedJob(): Job = TODO()

private fun AnyCoroutineStep.contextReferring(next: SchedulerStep?): Any? = TODO()

private suspend fun CoroutineScope.take(next: AnyCoroutineStep) {}

private suspend fun CoroutineScope.take(next: SchedulerStep, job: Job, context: Any?) {}

private suspend fun AnyCoroutineStep.isCurrentlyTrueGiven(predicate: JobPredicate) =
    predicate(markedJob())

private suspend fun AnyCoroutineStep.isCurrentlyFalseGiven(predicate: JobPredicate) =
    predicate(markedJob()).not()

private suspend fun AnyCoroutineStep.isCurrentlyFalseReferring(target: SchedulerStep) =
    currentConditionReferring(target).not()

private suspend fun AnyCoroutineStep.currentCondition() = true

private suspend fun AnyCoroutineStep.currentConditionReferring(target: SchedulerStep) = true

private suspend fun AnyCoroutineStep.accept() {}

private suspend fun AnyCoroutineStep.acceptOnTrue() {
    /* current context must resolve first then provide the next step */ }

private suspend fun AnyCoroutineStep.acceptOnFalse() {}

private suspend fun AnyCoroutineStep.acceptOnFalseReferring(target: SchedulerStep) {
    /* target may be switched in-place here */
    target.annotatedOrCurrentScope()
        .take(target, markedJob(), contextReferring(target)) }

private suspend fun AnyCoroutineStep.reject() {}

private suspend fun AnyCoroutineStep.rejectOnTrue() {}

private suspend fun AnyCoroutineStep.rejectOnFalse() {}

private suspend fun AnyCoroutineStep.rejectOnFalseReferring(target: SchedulerStep) {
    /* target must be used to find the next step in current context */ }

private fun AnyCoroutineStep.annotatedOrCurrentScope(): CoroutineScope = TODO()

private fun SchedulerStep.annotatedOrCurrentScope(): CoroutineScope = TODO()

private fun AnyCoroutineStep.annotatedOrCurrentScopeReferring(target: SchedulerStep): CoroutineScope = TODO()

private fun SchedulerStep.annotatedOrCurrentScopeReferring(target: AnyCoroutineStep): CoroutineScope = TODO()

infix fun Job?.then(next: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().then(next) } }

infix fun Job?.after(prev: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().after(prev) } }

infix fun Job?.given(predicate: JobPredicate) = this?.let {
    attachToElement {
        markedCoroutineStep().given(predicate) } }

infix fun Job?.unless(predicate: JobPredicate) = this?.let {
    attachToElement {
        markedCoroutineStep().unless(predicate) } }

infix fun Job?.otherwise(next: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().otherwise(next) } }

infix fun Job?.onCancel(action: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().onCancel(action) } }

infix fun Job?.onError(action: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().onError(action) } }

infix fun Job?.onTimeout(action: SchedulerStep) = this?.let {
    attachToElement {
        markedCoroutineStep().onTimeout(action) } }

infix fun AnyCoroutineStep?.then(next: SchedulerStep): CoroutineStep? = this?.let { prev ->
    attachToContext {
        prev.annotatedOrCurrentScopeReferring(next)
            .take(prev)
        next.annotatedOrCurrentScope()
            .take(next, currentJob(), prev.contextReferring(next)) } }

infix fun AnyCoroutineStep?.after(prev: SchedulerStep): CoroutineStep? = this?.let { next ->
    attachToContext {
        prev.annotatedOrCurrentScopeReferring(next)
            .take(prev, currentJob(), next.contextReferring(prev))
        next.annotatedOrCurrentScope()
            .take(next) } }

infix fun AnyCoroutineStep?.given(predicate: JobPredicate): CoroutineStep? = this?.let { cond ->
    attachToContext {
        if (cond.isCurrentlyTrueGiven(predicate))
            cond.acceptOnTrue()
        else cond.rejectOnTrue() } }

infix fun AnyCoroutineStep?.unless(predicate: JobPredicate): CoroutineStep? = this?.let { cond ->
    attachToContext {
        if (cond.isCurrentlyFalseGiven(predicate))
            cond.acceptOnFalse()
        else cond.rejectOnFalse() } }

infix fun AnyCoroutineStep?.otherwise(next: SchedulerStep): CoroutineStep? = this?.let { cond ->
    attachToContext {
        if (cond.isCurrentlyFalseReferring(next))
            cond.acceptOnFalseReferring(next)
        else cond.rejectOnFalseReferring(next) } }

infix fun AnyCoroutineStep?.onCancel(action: SchedulerStep): CoroutineStep? = TODO()

infix fun AnyCoroutineStep?.onError(action: SchedulerStep): CoroutineStep? = TODO()

infix fun AnyCoroutineStep?.onTimeout(action: SchedulerStep): CoroutineStep? = TODO()

infix fun Job?.thenJob(next: Job) = this

infix fun Job?.afterJob(prev: Job) = this

infix fun Job?.givenJob(predicate: JobPredicate) = this

infix fun Job?.unlessJob(predicate: JobPredicate) = this

infix fun Job?.otherwiseJob(next: Job) = this

infix fun Job?.onCancelJob(action: Job) = this

infix fun Job?.onErrorJob(action: Job) = this

infix fun Job?.onTimeoutJob(action: Job) = this

// from this point on, job controller handles the execution of each step and
// following a structured form that was built they react to any other continuation

infix fun CoroutineScope.diverge(step: SchedulerStep): CoroutineStep? = TODO()

infix fun CoroutineScope.diverge(job: Job): Job? = TODO()

infix fun CoroutineStep?.onConverge(action: SchedulerStep): CoroutineStep? = this

infix fun Job?.onConvergeJob(action: Job) = this

fun CoroutineScope?.converge(job: Job, context: Any?) {}

fun CoroutineScope.enact(job: Job, context: Any?) {}

fun CoroutineScope.enact(job: Job, exit: ThrowableFunction? = null) {}

fun CoroutineScope.error(job: Job, context: Any?) {}

internal fun CoroutineScope.error(job: Job, exit: ThrowableFunction? = null) {}

fun CoroutineScope.retry(job: Job, context: Any?) {}

internal fun CoroutineScope.retry(job: Job, exit: ThrowableFunction? = null) {}

fun CoroutineScope.close(job: Job, context: Any?) {}

internal fun CoroutineScope.close(job: Job, exit: ThrowableFunction? = null) {}

internal fun CoroutineScope.keepAlive(job: Job) = keepAliveNode(job.node)

fun CoroutineScope.keepAliveOrClose(job: Job) = keepAliveOrCloseNode(job.node)

internal fun CoroutineScope.keepAliveNode(node: SchedulerNode): Boolean = false

internal fun CoroutineScope.keepAliveOrCloseNode(node: SchedulerNode) =
    keepAliveNode(node) || node.close()

internal fun SchedulerNode.close(): Boolean = true

internal fun Job.close(node: SchedulerNode): Boolean = true

fun Job.close() {}

internal val Job.node: SchedulerNode
    get() = TODO()

internal fun LifecycleOwner.detach(job: Job? = null) {}

internal fun LifecycleOwner.reattach(job: Job? = null) {}

internal fun LifecycleOwner.close(job: Job? = null) {}

internal fun LifecycleOwner.detach(node: SchedulerNode) {}

fun LifecycleOwner.reattach(node: SchedulerNode) {}

fun LifecycleOwner.close(node: SchedulerNode) {}

fun CoroutineScope.change(event: Transit) =
    EventBus.commit(event)

fun CoroutineScope.change(stage: ContextStep) =
    EventBus.commit(stage)

internal fun <R> CoroutineScope.change(member: KFunction<R>, stage: ContextStep) =
    EventBus.commit(stage)

internal fun <R> CoroutineScope.change(owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.commit(stage)

internal fun <R> CoroutineScope.change(ref: WeakContext, member: KFunction<R>, stage: ContextStep) =
    EventBus.commit(stage)

internal fun <R> CoroutineScope.change(ref: WeakContext, owner: LifecycleOwner, member: KFunction<R>, stage: ContextStep) =
    EventBus.commit(stage)

internal suspend fun CoroutineScope.repeatSuspended(scope: CoroutineScope = this, predicate: PredicateFunction = @Tag(IS_ACTIVE) { isActive }, delayTime: DelayFunction = @Tag(YIELD) { 0L }, block: JobFunction) {
    markTagsForJobRepeat(predicate, delayTime, block, currentJob())
    while (predicate()) {
        block(scope)
        if (isActive)
            delayOrYield(delayTime()) } }

internal suspend fun delayOrYield(dt: Long = 0L) {
    if (dt > 0) delay(dt)
    else if (dt == 0L) yield() }

suspend fun CoroutineScope.currentContext() =
    currentJob()[CONTEXT].asContext()!!

private suspend fun CoroutineScope.registerContext(context: WeakContext) {
    currentJob()[CONTEXT] = context }

internal val SequencerScope.isActive
    get() = Sequencer { isCancelled } == false

internal fun SequencerScope.cancel() =
    Sequencer.cancel()

internal fun SequencerScope.commit(vararg tag: Tag) =
    commit(*tag.mapToStringArray())

internal fun LiveWork.attach(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attach(this, tag)

internal fun LiveWork.attachOnce(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attachOnce(this, tag)

internal fun LiveWork.attachAfter(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attachAfter(this, tag)

internal fun LiveWork.attachBefore(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attachBefore(this, tag)

internal fun LiveWork.attachOnceAfter(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attachOnceAfter(this, tag)

internal fun LiveWork.attachOnceBefore(tag: String? = null, owner: LifecycleOwner? = null) =
    Sequencer.attachOnceBefore(this, tag)

internal fun LiveWork.detach() {}

internal fun LiveWork.close() {}

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.toLiveWork(async: Boolean = false) =
    LiveWork(@Keep { first.asType() }, second.asType(), async)

internal fun <T, R> capture(context: CoroutineContext, step: suspend LiveDataScope<T>.() -> Unit, capture: (T) -> R) =
    liveData(context, block = step) to capture

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observer: Observer<T> = disposerOf(this)): Observer<T> {
    first.observe(owner, observer)
    return observer }

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.dispose(owner: LifecycleOwner, disposer: Observer<T> = owner.disposerOf(this)) =
    observe(owner, disposer)

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: Observer<T> = disposerOf(this)): Observer<T> {
    first.observeForever(observer)
    return observer }

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(owner: LifecycleOwner, observer: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(owner, observer(this))

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.observe(observer: (Pair<LiveData<T>, (T) -> R>) -> Observer<T> = ::disposerOf) =
    observe(observer(this))

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObserver(observer: Observer<T>) =
    first.removeObserver(observer)

internal fun <T, R> Pair<LiveData<T>, (T) -> R>.removeObservers(owner: LifecycleOwner) =
    first.removeObservers(owner)

internal fun <T, R> observerOf(liveStep: Pair<LiveData<T>, (T) -> R>) =
    Observer<T> { liveStep.second(it) }

private fun <T, R> disposerOf(liveStep: Pair<LiveData<T>, (T) -> R>) =
    object : Observer<T> {
        override fun onChanged(value: T) {
            val (step, capture) = liveStep
            step.removeObserver(this)
            capture(value) } }

private fun <T, R> LifecycleOwner.disposerOf(liveStep: Pair<LiveData<T>, (T) -> R>) =
    Observer<T> { value ->
        val (step, capture) = liveStep
        step.removeObservers(this)
        capture(value) }

internal infix fun LiveWork.then(next: SequencerStep): LiveWork = this

internal infix fun LiveWork.then(next: LiveWorkFunction): LiveWork = this

internal infix fun LiveWork.after(prev: SequencerStep): LiveWork = this

internal infix fun LiveWork.given(predicate: LiveWorkPredicate): LiveWork = this

internal infix fun LiveWork.unless(predicate: LiveWorkPredicate): LiveWork = this

internal infix fun LiveWork.otherwise(next: SequencerStep): LiveWork = this

internal infix fun LiveWork.onCancel(action: SequencerStep): LiveWork = this

internal infix fun LiveWork.onError(action: SequencerStep): LiveWork = this

internal infix fun LiveWork.onTimeout(action: SequencerStep): LiveWork = this

internal suspend fun SequencerScope.change(event: Transit) =
    reset {
    EventBus.commit(event) }

internal suspend fun SequencerScope.change(stage: ContextStep) =
    resetByTag(getTag(stage)) {
    EventBus.commit(stage) }

internal suspend fun <R> SequencerScope.capture(block: () -> R) =
    emit {
        reset()
        block() }

internal suspend fun <R> SequencerScope.captureByTag(tag: String, block: () -> R) =
    emit {
        resetByTag(tag)
        block() }

private suspend inline fun <R> SequencerScope.reset(block: () -> R): R {
    reset()
    return block() }

private suspend inline fun <R> SequencerScope.resetByTag(tag: String, block: () -> R): R {
    resetByTag(tag)
    return block() }

internal fun SequencerScope.reset() = iso.consolator.reset()
internal fun SequencerScope.resetByTag(tag: String) = iso.consolator.resetByTag(tag)

private fun reset() { sequencer?.reset() }
private fun resetByTag(tag: String) { sequencer?.resetByTag(tag) }

private fun getTag(stage: ContextStep): String = TODO()

private fun Step.toLiveStep(): SequencerStep = { invoke() }

private inline fun <R> sequencer(block: Sequencer.() -> R) = sequencer?.block()

private var sequencer: Sequencer? = null
    get() = field.singleton().also { field = it }

private class Sequencer : Synchronizer<LiveWork>, Transactor<Int, Boolean?>, PriorityQueue<Int>, AdjustOperator<LiveWork, Int> {
    constructor() : this(DEFAULT_OBSERVER)

    private constructor(observer: StepObserver) {
        this.observer = observer }

    private val observer: StepObserver
    override var queue: IntMutableList = LinkedList()
    private var seq: LiveSequence = mutableListOf()

    private var ln = -1
        get() = queue.firstOrNull() ?: (field + 1)

    private val work
        get() = synchronize { adjust(queue.removeFirst()) }
            .also(::ln::set)

    private var latestStep: LiveStep? = null
    private var latestCapture: Any? = null

    private fun getLifecycleOwner(step: Int): LifecycleOwner? = null

    fun setLifecycleOwner(step: Int, owner: LifecycleOwner) {}

    fun removeLifecycleOwner(step: Int) {}

    fun clearLifecycleOwner(owner: LifecycleOwner) {}

    private val mLock: Any get() = seq

    override fun <R> synchronize(lock: LiveWork?, block: () -> R) =
        synchronized(mLock, block)

    private fun init() {
        ln = -1
        clearFlags()
        clearLatestObjects() }

    fun start() {
        init()
        resume() }

    fun resume(index: Int) {
        queue.add(index)
        resume() }

    fun resume(tag: String) {
        fun getIndex(tag: String): Int = TODO()
        resume(getIndex(tag)) }

    fun resume(tag: Tag) =
        resume(tag.string)

    fun resume() {
        isActive = true
        advance() }

    fun resumeAsync() =
        synchronize { resume() }

    var activate = fun() = prepare()
    var next = fun(step: Int) = jump(step)
    var run = fun(step: Int) = commit(step)
    var bypass = fun(step: Int) = capture(step)
    var finish = fun() = end()

    private tailrec fun advance() { tryAvoiding {
        activate() // periodic pre-configuration can be done here
        if (isCompleted) return
        while (next(ln) ?: return /* or issue task resolution */) {
            yield()
            work.let { run(it) ?: bypass(it) } || return }
        isCompleted = finish() }
        if (!isCompleted) advance() }

    fun prepare() { if (ln < 0) ln = -1 }

    fun jump(step: Int) =
        if (hasError) null
        else synchronize {
            val step = adjust(step)
            (step >= 0 && step < seq.size &&
            (!isObserving || seq[step].isAsynchronous()))
            .also { allowed ->
                if (allowed) queue.add(step) } }

    override fun commit(step: Int): Boolean? {
        var step = step
        val (liveStep, _, async) = synchronize {
            step = adjust(step)
            seq[step] }
        tryPropagating({
            val liveStep = liveStep() // process tags to reuse live step
            yield()
            latestStep = liveStep // live step <-> work
            if (liveStep !== null)
                synchronize {
                    getLifecycleOwner(adjust(step)) }
                ?.let { owner ->
                    liveStep.observe(owner, observer) }
                ?: liveStep.observeForever(observer)
            else return null
        }, { ex ->
            error(ex)
            return false
        })
        isObserving = true
        return async }

    fun capture(step: Int): Boolean {
        val work = synchronize { seq[adjust(step)] }
        val capture = work.second
        yield()
        latestCapture = capture
        val async = capture?.invoke(work)
        return if (async is Boolean) async
        else false }

    fun end() = queue.isEmpty() && !isObserving

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
        reset<CancellationException, _>(::reset, ::cancel, block)

    suspend inline fun <R> SequencerScope.resetOnError(block: () -> R) =
        reset<Throwable, _>(::reset, ::error, block)

    suspend inline fun <R> SequencerScope.resetByTagOnCancel(tag: String, block: () -> R) =
        resetByTag<CancellationException, _>(tag, ::resetByTag, ::cancelByTag, block)

    suspend inline fun <R> SequencerScope.resetByTagOnError(tag: String, block: () -> R) =
        resetByTag<Throwable, _>(tag, ::resetByTag, ::errorByTag, block)

    suspend inline fun <reified T : Throwable, R> SequencerScope.reset(reset: Work, register: (Throwable) -> Unit, block: () -> R) =
        tryCatching<T, _>(block) { ex ->
            reset()
            register(ex)
            throw interrupt(ex) }

    suspend inline fun <reified T : Throwable, R> SequencerScope.resetByTag(tag: String, reset: (String) -> Unit, register: (String, Throwable) -> Unit, block: () -> R) =
        tryCatching<T, _>(block) { ex ->
            reset(tag)
            register(tag, ex)
            throw interruptByTag(tag, ex) }

    // preserve tags
    private fun resettingFirstly(step: SequencerStep) = step after { reset() }
    private fun resettingLastly(step: SequencerStep) = step then { reset() }
    private fun resettingByTagFirstly(step: SequencerStep) = step after { resetByTag(getTag(step)) }
    private fun resettingByTagLastly(step: SequencerStep) = step then { resetByTag(getTag(step)) }

    private fun getTag(step: SequencerStep) = returnItsTag(step)!!

    var isActive = false
    var isObserving = false
    var isCompleted = false
    var isCancelled = false
    var hasError = false
    var ex: Throwable? = null

    private fun yield() { if (isCancelled) throw Propagate() }

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

    companion object {
        var DEFAULT_OBSERVER = StepObserver {
            it?.block() /* or apply (live step) capture function internally */ }

        const val ATTACHED_ALREADY = -1

        fun attach(step: LiveWork, tag: String? = null) = invoke { attach(step, tag) }
        fun attachOnce(step: LiveWork, tag: String? = null) = invoke { attachOnce(step, tag) }
        fun attachAfter(step: LiveWork, tag: String? = null) = invoke { attachAfter(step, tag) }
        fun attachBefore(step: LiveWork, tag: String? = null) = invoke { attachBefore(step, tag) }
        fun attachOnceAfter(step: LiveWork, tag: String? = null) = invoke { attachOnceAfter(step, tag) }
        fun attachOnceBefore(step: LiveWork, tag: String? = null) = invoke { attachOnceBefore(step, tag) }

        fun start() = invoke { start() }
        fun resume(tag: String) = invoke { resume(tag) }
        fun resume(tag: Tag) = invoke { resume(tag) }
        fun resume() = invoke { resume() }
        fun resumeAsync() = invoke { resumeAsync() }

        val isActive get() = invoke { isActive }

        fun cancel() = invoke { isCancelled = true }

        operator fun <R> invoke(work: Sequencer.() -> R) = sequencer?.work()
    }

    override fun adjust(index: Int) = index

    private fun LiveSequence.attach(element: LiveWork) =
        add(element)
    private fun LiveSequence.attach(index: Int, element: LiveWork) =
        add(index, element)

    private fun LiveWork.setTag(tag: String?) = this

    override fun attach(step: LiveWork, vararg args: Any?) =
        synchronize { with(seq) {
            attach(step)
            size - 1 }.also { index ->
            markTagsForSeqAttach(args.firstOrNull(), index, step) } }

    fun attachOnce(work: LiveWork) =
        synchronize {
            if (work.isNotAttached())
                attach(work)
            else ATTACHED_ALREADY }

    fun attachOnce(work: LiveWork, tag: String? = null): Int = TODO()

    fun attachOnce(range: IntRange, work: LiveWork) =
        synchronize {
            if (work.isNotAttached(range))
                attach(work)
            else ATTACHED_ALREADY }

    fun attachOnce(first: Int, last: Int, work: LiveWork) =
        synchronize {
            if (work.isNotAttached(first, last))
                attach(work)
            else ATTACHED_ALREADY }

    override fun attach(index: Int, step: LiveWork, vararg args: Any?) =
        synchronize { with(seq) {
            attach(index, step)
            markTagsForSeqAttach(args.firstOrNull(), index, step)
            // remark proceeding work in sequence for adjustment
            index } }

    fun attachOnce(index: Int, work: LiveWork) =
        synchronize {
            if (work.isNotAttached(index)) {
                attach(index, work)
                index }
            else ATTACHED_ALREADY }

    fun attachOnce(range: IntRange, index: Int, work: LiveWork) =
        synchronize {
            if (work.isNotAttached(range, index))
                attach(index, work)
            else ATTACHED_ALREADY }

    fun attachOnce(first: Int, last: Int, index: Int, work: LiveWork) =
        synchronize {
            if (work.isNotAttached(first, last, index))
                attach(index, work)
            else ATTACHED_ALREADY }

    fun attachAfter(work: LiveWork, tag: String? = null) =
        attach(after, work, tag)

    fun attachBefore(work: LiveWork, tag: String? = null) =
        attach(before, work, tag)

    fun attachOnceAfter(work: LiveWork) =
        attachOnce(after, work)

    fun attachOnceAfter(work: LiveWork, tag: String? = null): Int = TODO()

    fun attachOnceBefore(work: LiveWork) =
        attachOnce(before, work)

    fun attachOnceBefore(work: LiveWork, tag: String? = null): Int = TODO()

    private fun stepAfterTrackingTagsForSeqLaunch(step: SequencerStep, index: IntFunction, context: CoroutineContext? = null) =
        (step after { currentJob().let { job ->
            synchronize { markTagsForSeqLaunch(step, adjust(index()), context, job) } } })!!

    fun attach(async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) }
            .also { index = attach(it, returnItsTag(step)) } }

    fun attach(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) },
            capture, async)
            .also { index = attach(it, returnItsTag(step)) } }

    fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) }
            .also { index = attach(it, returnItsTag(step)) } }

    fun attach(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) },
            capture, async)
            .also { index = attach(it, returnItsTag(step)) } }

    fun attach(index: Int, async: Boolean = false, step: SequencerStep) =
        stepToNull(async) { liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) }
            .also { attach(index, it, returnItsTag(step)) }

    fun attach(index: Int, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
        Triple({ liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) },
            capture, async)
            .also { attach(index, it, returnItsTag(step)) }

    fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep) =
        stepToNull(async) { liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) }
            .also { attach(index, it, returnItsTag(step)) }

    fun attach(index: Int, context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction) =
        Triple({ liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) },
            capture, async)
            .also { attach(index, it, returnItsTag(step)) }

    fun attachAfter(async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) }
            .also { index = attachAfter(it, returnItsTag(step)) } }

    fun attachAfter(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) },
            capture, async)
            .also { index = attachAfter(it, returnItsTag(step)) } }

    fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) }
            .also { index = attachAfter(it, returnItsTag(step)) } }

    fun attachAfter(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) },
            capture, async)
            .also { index = attachAfter(it, returnItsTag(step)) } }

    fun attachBefore(async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) }
            .also { index = attachBefore(it, returnItsTag(step)) } }

    fun attachBefore(async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index })(step) }) },
            capture, async)
            .also { index = attachBefore(it, returnItsTag(step)) } }

    fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep): LiveWork {
        var index = -1
        return stepToNull(async) { liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) }
            .also { index = attachBefore(it, returnItsTag(step)) } }

    fun attachBefore(context: CoroutineContext, async: Boolean = false, step: SequencerStep, capture: CaptureFunction): LiveWork {
        var index = -1
        return Triple({ liveData(context, block = {
            stepAfterTrackingTagsForSeqLaunch(step, { index }, context)(step) }) },
            capture, async)
            .also { index = attachBefore(it, returnItsTag(step)) } }

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

    private fun stepToNull(async: Boolean = false, step: LiveStepPointer) = Triple(step, nullBlock, async)
    private fun nullStepTo(block: CaptureFunction) = Triple(nullStep, block, false)

    private val nullStep: LiveStepPointer = @Tag(NULL_STEP) { null }
    private val nullBlock: CaptureFunction? = null

    private fun LiveWork.isAsynchronous() = third

    private fun LiveWork.isSameWork(work: LiveWork) =
        this === work || (first === work.first && second === work.second)

    private fun LiveWork.isNotSameWork(work: LiveWork) =
        this !== work && first !== work.first && second !== work.second

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

    private inline fun none(index: Int, predicate: LiveWorkPredicate) = with(seq) { when {
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

    private val leading
        get() = 0 until with(seq) { if (ln < size) ln else size }

    private val trailing
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

private var jobs: JobFunctionSet? = null

internal operator fun Job.get(tag: String) =
    jobs?.find { tag == it.first() }
        ?.second.asAnyArray()?.get(1)

internal operator fun Job.set(tag: String, value: Any?) {
    // addressable layer work
    value.markTag(tag) }

private fun JobFunctionSet.save(function: AnyKCallable, tag: String) =
    function.tag.apply {
        save(function,
            combineTags(tag, this?.string),
            this?.keep ?: true) }

private fun JobFunctionSet.save(function: AnyKCallable, vararg tag: String?) {}

private fun JobFunctionSet.save(self: AnyKCallable, tag: Tag?) =
    if (tag !== null)
        with(tag) { save(self, string, keep) }
    else
        save(self, null, false)

private fun JobFunctionSet.save(function: AnyKCallable, tag: String?, keep: Boolean) =
    add((tag?.let { { it } } ?: currentThreadJob().toJobId()::toString) to arrayOf(function, keep))

private fun combineTags(tag: String, self: Any?) =
    if (self === null) tag
    else "$tag.$self"

private fun returnItsTag(it: Any?) = it.asNullable().tag?.string

internal fun AnyKCallable.markTag() = tag.also { jobs?.save(this, it) }

internal fun Any.markTag() = asCallable().markTag()

internal fun Any?.markTag(tag: String) = jobs?.save(asNullable(), tag)

internal fun Any?.markSequentialTag(vararg tag: String?): String? =
    tag.first()?.let { tag ->
    combineTags(tag, returnItsTag(this))
    .also { markTag(it) } }

private fun <T> T.applyMarkTag(tag: String) = apply { markTag(tag) }

private fun AnyStep?.markTagForSchExec() = applyMarkTag(SCH_EXEC)
private fun AnyStep.markTagForSchPost() = applyMarkTag(SCH_POST)

private fun AnyCoroutineStep.markTagForFloLaunch() = applyMarkTag(FLO_LAUNCH)
private fun AnyCoroutineStep.markTagForSchCommit() = applyMarkTag(SCH_COMMIT)
private fun AnyCoroutineStep.markTagForSchLaunch() = applyMarkTag(SCH_LAUNCH)
private fun AnyCoroutineStep.markTagForSchPost() = applyMarkTag(SCH_POST)
private fun AnyCoroutineStep.markTagForSvcCommit() = applyMarkTag(SVC_COMMIT)

private fun SequencerStep.setTagTo(step: Step) = this

private fun getTag(callback: Runnable): String? = TODO()
private fun getTag(msg: Message): String? = TODO()
private fun getTag(what: Int): String? = TODO()

internal fun markTags(vararg function: Any?) {
    when (val context = function.firstOrNull()) {
        (context === JOB_LAUNCH) ->
            markTagsForJobLaunch(*function, i = 1)
        (context === SEQ_LAUNCH) ->
            markTagsForSeqLaunch(*function, i = 1)
        (context === JOB_REPEAT) ->
            markTagsForJobRepeat(*function, i = 1)
        (context === CLK_ATTACH) ->
            markTagsForClkAttach(*function, i = 1)
        (context === SEQ_ATTACH) ->
            markTagsForSeqAttach(*function, i = 1)
        (context === CTX_REFORM) ->
            markTagsForCtxReform(*function, i = 1)
        else ->
            function.map(Any?::asNullable)
                .forEach(AnyKCallable::markTag) } }

private fun markTagsForJobLaunch(vararg function: Any?, i: Int = 0) =
    function[i + 2]?.markTag()?.also { step ->
    val stepTag = step.string
    val jobId = function[i + 4]?.let { job ->
        jobs?.save(job.asCallable(), "$stepTag.$JOB", step.keep)
        job.toJobId() } /* job */
    function[i]?.let { context ->
        jobs?.save(context.asCallable(), "$stepTag@$jobId.$CONTEXT", false) } /* context */
    function[i + 1]?.let { start ->
        jobs?.save(start.asCallable(), "$stepTag@$jobId.$START", false) } /* start */ }

private fun markTagsForJobRepeat(vararg function: Any?, i: Int = 0) =
    function[i + 2]?.markTag()?.also { blockTag ->
    val blockTag = blockTag.string
    val jobId = function[i + 3].toJobId()
    function[i + 1]?.let { delay ->
        jobs?.save(delay.asCallable(), "$blockTag@$jobId.$DELAY") } /* delay */
    function[i]?.let { predicate ->
        jobs?.save(predicate.asCallable(), "$blockTag@$jobId.$PREDICATE") } /* predicate */ }

private fun markTagsForClkAttach(vararg function: Any?, i: Int = 0) =
    function[i + 1]?.let { step -> when (step) {
    is Runnable -> {
        val stepTag = getTag(step)
        jobs?.save(step.asCallable(), "$stepTag.$CALLBACK") /* callback */
        function[i]?.let { index ->
        jobs?.save(index.asCallable(), "$stepTag.$INDEX") } /* index */ }
    is Message -> {
        jobs?.save(step.asCallable(), "${getTag(step)}.$MSG") /* message */ }
    is Int ->
        jobs?.save(step.asCallable(), "${getTag(step)}.$WHAT" /* what */ )
    else -> null } }

private fun markTagsForSeqAttach(vararg function: Any?, i: Int = 0) =
    function[i]?.asString().let { stepTag ->
    val stepTag = stepTag ?: NULL_STEP
    function[i + 1]?.let { index ->
        jobs?.save(index.asCallable(), "$stepTag.$INDEX")
    function[i + 2]?.asLiveWork()?.let { work ->
        jobs?.save(work.asCallable(), "$stepTag#$index.$WORK") } } /* index & work */ }

private fun markTagsForSeqLaunch(vararg function: Any?, i: Int = 0) =
    function[i]?.markTag()?.also { stepTag ->
    val stepTag = stepTag.string
    val index = function[i + 1]?.asInt()!! // optionally, readjust by remarks or from seq here instead
    val jobId = function[i + 3].toJobId()
    function[i + 2]?.let { context ->
        jobs?.save(context.asNullable(), "$stepTag#$index@$jobId.$CONTEXT", false) } /* context */ }

private fun markTagsForCtxReform(vararg function: Any?, i: Int = 0) =
    function[i + 1].asString()?.let { stageTag ->
    combineTags(stageTag, function[i] /* id tag */) }?.also { stageTag ->
    val jobId = function[i + 3]?.let { job ->
        jobs?.save(job.asCallable(), "$stageTag.$JOB", false)
        job.toJobId() } /* job */
    function[i + 2]?.let { form ->
        jobs?.save(form.asCallable(), "$stageTag@$jobId.$FORM", false) } /* form */ }

inline infix fun <R, S> (suspend () -> R)?.then(crossinline next: suspend () -> S): (suspend () -> S)? = this?.let { {
    this@then()
    next() } }

inline infix fun <R, S> (suspend () -> R)?.after(crossinline prev: suspend () -> S): (suspend () -> R)? = this?.let { {
    prev()
    this@after() } }

inline infix fun <R, S> (suspend () -> R)?.thru(crossinline next: suspend (R) -> S): (suspend () -> S)? = this?.let { {
    next(this@thru()) } }

inline fun <R> (suspend () -> R)?.given(crossinline predicate: Predicate, crossinline fallback: suspend () -> R): (suspend () -> R)? = this?.let { {
    if (predicate()) this@given() else fallback() } }

inline fun <R> (suspend () -> R)?.unless(crossinline predicate: Predicate, crossinline fallback: suspend () -> R): (suspend () -> R)? = this?.let { {
    if (predicate().not()) this@unless() else fallback() } }

inline infix fun Step?.given(crossinline predicate: Predicate) = this?.let {
    given(predicate, emptyStep) }

inline infix fun Step?.unless(crossinline predicate: Predicate) = this?.let {
    unless(predicate, emptyStep) }

inline infix fun <T, R, S> (suspend T.() -> R)?.then(crossinline next: suspend T.() -> S): (suspend T.() -> S)? = this?.let { {
    this@then()
    next() } }

inline infix fun <T, R, S> (suspend T.() -> R)?.after(crossinline prev: suspend T.() -> S): (suspend T.() -> R)? = this?.let { {
    prev()
    this@after() } }

inline infix fun <T, R, S> (suspend T.() -> R)?.thru(crossinline next: suspend (R) -> S): (suspend T.() -> S)? = this?.let { {
    next(this@thru()) } }

inline fun <T, R> (suspend T.() -> R)?.given(crossinline predicate: Predicate, crossinline fallback: suspend T.() -> R): (suspend T.() -> R)? = this?.let { {
    if (predicate()) this@given() else fallback() } }

inline fun <T, R> (suspend T.() -> R)?.unless(crossinline predicate: Predicate, crossinline fallback: suspend T.() -> R): (suspend T.() -> R)? = this?.let { {
    if (predicate().not()) this@unless() else fallback() } }

inline infix fun <T, U, R, S> (suspend T.(U) -> R)?.then(crossinline next: suspend T.(U) -> S): (suspend T.(U) -> S)? = this?.let { {
    this@then(it)
    next(it) } }

inline infix fun <T, U, R, S> (suspend T.(U) -> R)?.after(crossinline prev: suspend T.(U) -> S): (suspend T.(U) -> R)? = this?.let { {
    prev(it)
    this@after(it) } }

inline infix fun <T, U, R, S> (suspend T.(U) -> R)?.thru(crossinline next: suspend (R) -> S): (suspend T.(U) -> S)? = this?.let { {
    next(this@thru(it)) } }

inline fun <T, U, R> (suspend T.(U) -> R)?.given(crossinline predicate: Predicate, crossinline fallback: suspend T.(U) -> R): (suspend T.(U) -> R)? = this?.let { {
    if (predicate()) this@given(it) else fallback(it) } }

inline fun <T, U, R> (suspend T.(U) -> R)?.unless(crossinline predicate: Predicate, crossinline fallback: suspend T.(U) -> R): (suspend T.(U) -> R)? = this?.let { {
    if (predicate().not()) this@unless(it) else fallback(it) } }

inline infix fun <R, S> (() -> R)?.then(crossinline next: () -> S): (() -> S)? = this?.let { {
    this@then()
    next() } }

inline infix fun <R, S> (() -> R)?.after(crossinline prev: () -> S): (() -> R)? = this?.let { {
    prev()
    this@after() } }

inline infix fun <R, S> (() -> R)?.thru(crossinline next: (R) -> S): (() -> S)? = this?.let { {
    next(this@thru()) } }

inline fun <R> (() -> R)?.given(crossinline predicate: Predicate, crossinline fallback: () -> R): (() -> R)? = this?.let { {
    if (predicate()) this@given() else fallback() } }

inline fun <R> (() -> R)?.unless(crossinline predicate: Predicate, crossinline fallback: () -> R): (() -> R)? = this?.let { {
    if (predicate().not()) this@unless() else fallback() } }

inline infix fun AnyFunction?.given(crossinline predicate: Predicate) = this?.let {
    given(predicate, emptyWork) }

inline infix fun AnyFunction?.unless(crossinline predicate: Predicate) = this?.let {
    unless(predicate, emptyWork) }

inline infix fun <T, R, S> ((T) -> R)?.thru(crossinline next: (R) -> S): ((T) -> S)? = this?.let { {
    next(this@thru(it)) } }

internal fun <R> KCallable<R>.with(vararg args: Any?): () -> R = {
    this@with.call(*args) }

internal fun <R> call(vararg args: Any?): (KCallable<R>) -> R = {
    it.call(*args) }

internal inline fun <L : Any, R, S : R> transact(noinline lock: () -> L, predicate: (L?) -> Boolean = { true }, block: (L) -> R, fallback: () -> S? = { null }): R? {
    if (predicate(null))
        lock().let { key ->
            synchronized(key) {
                if (predicate(key)) return block(key) } }
    return fallback() }

internal inline fun <R, S : R> transact(state: State, predicate: StatePredicate, block: (Any) -> R, fallback: () -> S? = { null }): R? {
    if (predicate(state, null))
        state().let { lock ->
            synchronized(lock) {
                if (predicate(state, lock)) return block(lock) } }
    return fallback() }

internal inline fun <R> commitAsync(lock: Any, predicate: Predicate, block: () -> R) {
    if (predicate())
        synchronized(lock) {
            if (predicate()) block() } }

internal inline fun <R, S : R> commitAsyncForResult(lock: Any, predicate: Predicate, block: () -> R, fallback: () -> S? = { null }): R? {
    if (predicate())
        synchronized(lock) {
            if (predicate()) return block() }
    return fallback() }

internal fun Any?.toJobId() = asJob().hashCode()

internal suspend fun currentJob() = currentCoroutineContext().job
internal fun currentThreadJob() = ::currentJob.block()

internal fun <T> blockOf(step: suspend CoroutineScope.() -> T): () -> T = { runBlocking(block = step) }
internal fun <T> runnableOf(step: suspend CoroutineScope.() -> T) = Runnable { runBlocking(block = step) }
internal fun <T> safeRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafely(blockOf(step)) }
internal fun <T> interruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { tryInterrupting(step) }
internal fun <T> safeInterruptingRunnableOf(step: suspend CoroutineScope.() -> T) = Runnable { trySafelyInterrupting(step) }

internal fun <T> blockOf(step: suspend () -> T): () -> T = step::block
internal fun <T> runnableOf(step: suspend () -> T) = Runnable { step.block() }
internal fun <T> safeRunnableOf(step: suspend () -> T) = Runnable { trySafely(blockOf(step)) }
internal fun <T> interruptingRunnableOf(step: suspend () -> T) = Runnable { tryInterrupting(blockOf(step)) }
internal fun <T> safeInterruptingRunnableOf(step: suspend () -> T) = Runnable { trySafelyInterrupting(blockOf(step)) }

internal fun <R> (suspend () -> R).block() = runBlocking { invoke() }
internal fun <T, R> (suspend T.() -> R).block(scope: T) = runBlocking { invoke(scope) }
internal fun <T, U, R> (suspend T.(U) -> R).block(scope: T, value: U) = runBlocking { invoke(scope, value) }
internal fun <T, U, R> (suspend T.(U) -> R).block(scope: () -> T, value: U) = runBlocking { invoke(scope(), value) }
internal fun <T, U, R> (suspend T.(U) -> R).block(scope: KCallable<T>, value: U) = runBlocking { invoke(scope.call(), value) }

private fun AnyStep.post() = Scheduler.postValue(this)
private fun AnyStep.postAhead() { Scheduler.value = this }

fun schedule(step: Step) =
    repostByPreference(step, AnyStep::post, ::handle)

internal fun scheduleAhead(step: Step) =
    repostByPreference(step, AnyStep::postAhead, ::handleAhead)

internal fun repost(step: CoroutineStep) =
    repostByPreference(step, AnyStep::post, ::handle)

internal fun repostAhead(step: CoroutineStep) =
    repostByPreference(step, AnyStep::postAhead, ::handleAhead)

private fun repostByPreference(step: CoroutineStep, post: AnyStepFunction, handle: CoroutineFunction) =
    repostByPreference(
        { post(step.markTagForSchPost().toStep()) },
        { handle(step) })

private fun repostByPreference(step: Step, post: AnyStepFunction, handle: CoroutineFunction) =
    repostByPreference(
        { post(step.markTagForSchPost()) },
        { handle(step.toCoroutine()) })

private inline fun repostByPreference(post: Work, handle: Work) = when {
    !SchedulerScope.isClockPreferred &&
    SchedulerScope.isSchedulerObserved ->
        post()
    Clock.isRunning ->
        handle()
    SchedulerScope.isSchedulerObserved ->
        post()
    else ->
        currentThread.interrupt() }

private fun Step.toCoroutine(): CoroutineStep = { this@toCoroutine() }

private fun AnyCoroutineStep.toStep() = suspend { invoke(annotatedOrSchedulerScope()) }

// step <-> runnable
internal fun handle(step: AnyCoroutineStep) = post(runnableOf(step))
internal fun handleAhead(step: AnyCoroutineStep) = postAhead(runnableOf(step))
internal fun handleSafely(step: CoroutineStep) = post(safeRunnableOf(step))
internal fun handleAheadSafely(step: CoroutineStep) = postAhead(safeRunnableOf(step))
internal fun handleInterrupting(step: CoroutineStep) = post(interruptingRunnableOf(step))
internal fun handleAheadInterrupting(step: CoroutineStep) = postAhead(interruptingRunnableOf(step))
internal fun handleSafelyInterrupting(step: CoroutineStep) = post(safeInterruptingRunnableOf(step))
internal fun handleAheadSafelyInterrupting(step: CoroutineStep) = postAhead(safeInterruptingRunnableOf(step))

private fun Runnable.asCoroutine() =
    Clock.getCoroutine(this) ?: toCoroutine()

private fun Runnable.toCoroutine(): CoroutineStep = { run() }

// step <-> runnable
internal fun reinvoke(step: Step) = post(runnableOf(step))
internal fun reinvokeAhead(step: Step) = postAhead(runnableOf(step))
internal fun reinvokeSafely(step: Step) = post(safeRunnableOf(step))
internal fun reinvokeAheadSafely(step: Step) = postAhead(safeRunnableOf(step))
internal fun reinvokeInterrupting(step: Step) = post(interruptingRunnableOf(step))
internal fun reinvokeAheadInterrupting(step: Step) = postAhead(interruptingRunnableOf(step))
internal fun reinvokeSafelyInterrupting(step: Step) = post(safeInterruptingRunnableOf(step))
internal fun reinvokeAheadSafelyInterrupting(step: Step) = postAhead(safeInterruptingRunnableOf(step))

private fun Runnable.asStep() =
    Clock.getStep(this) ?: toStep()

private fun Runnable.toStep() = suspend { run() }

// runnable <-> message
internal fun post(callback: Runnable) = clock?.post?.invoke(callback)
internal fun postAhead(callback: Runnable) = clock?.postAhead?.invoke(callback)

private fun Runnable.asMessage() =
    with(Clock) { getCoroutine(this@asMessage)?.run(::getMessage) }

fun Runnable.start() {}

fun Runnable.startDelayed(delay: Long) {}

fun Runnable.startAtTime(uptime: Long) {}

private fun Runnable.detach(): Runnable? = null

private fun Runnable.close() {}

infix fun Runnable.then(next: Runnable): Runnable = this

infix fun Runnable.then(next: RunnableFunction): Runnable = this

infix fun Runnable.after(prev: Runnable): Runnable = this

infix fun Runnable.given(predicate: RunnablePredicate): Runnable = this

infix fun Runnable.unless(predicate: RunnablePredicate): Runnable = this

infix fun Runnable.otherwise(next: Runnable): Runnable = this

infix fun Runnable.otherwise(next: RunnableFunction): Runnable = this

infix fun Runnable.onError(action: Runnable): Runnable = this

infix fun Runnable.onTimeout(action: Runnable): Runnable = this

private fun Message.asStep() = callback.asStep()

private fun Message.asCoroutine() = callback.asCoroutine()

private fun Message.asRunnable() = callback

internal fun message(callback: Runnable): Message = TODO()

internal fun message(what: Int): Message = TODO()

fun Message.send() {}

fun Message.sendDelayed(delay: Long) {}

fun Message.sendAtTime(uptime: Long) {}

private fun Message.detach(): Message? = null

private fun Message.close() {}

internal infix fun Message.thenRun(next: Runnable): Message = this

internal infix fun Message.afterRun(prev: Runnable): Message = this

internal infix fun Message.otherwiseRun(next: Runnable): Message = this

internal infix fun Message.onErrorRun(action: Runnable): Message = this

internal infix fun Message.onTimeoutRun(action: Runnable): Message = this

internal infix fun Message.then(next: Message): Message = this

internal infix fun Message.then(next: Int): Message = this

internal infix fun Message.after(prev: Message): Message = this

internal infix fun Message.after(prev: Int): Message = this

internal infix fun Message.given(predicate: MessagePredicate): Message = this

internal infix fun Message.unless(predicate: MessagePredicate): Message = this

internal infix fun Message.otherwise(next: Message): Message = this

internal infix fun Message.otherwise(next: Int): Message = this

internal infix fun Message.onError(action: Message): Message = this

internal infix fun Message.onTimeout(action: Message): Message = this

internal infix fun Message.then(next: MessageFunction): Message = this

internal infix fun Message.otherwise(next: MessageFunction): Message = this

private var clock: Clock? = null
    get() = field.singleton().also { field = it }

internal open class Clock(
    name: String,
    priority: Int = currentThread.priority
) : HandlerThread(name, priority), Synchronizer<Any>, Transactor<Message, Any?>, PriorityQueue<Runnable>, AdjustOperator<Runnable, Number> {
    var handler: Handler? = null
    final override lateinit var queue: RunnableList

    init {
        this.priority = priority
        register() }

    constructor() : this(CLK)

    constructor(callback: Runnable) : this() {
        register(callback) }

    constructor(name: String, priority: Int = currentThread.priority, callback: Runnable) : this(name, priority) {
        register(callback) }

    var id = -1
        private set

    override fun start() {
        id = indexOf(queue)
        super.start() }

    fun alsoStart(): Clock {
        start()
        return this }

    fun startAsync() =
        commitAsync(this, { !isAlive }, ::start)

    fun alsoStartAsync(): Clock {
        startAsync()
        return this }

    val isStarted get() = id != -1
    val isNotStarted get() = id == -1
    var isRunning = false

    override fun run() {
        hLock = Lock.Open()
        handler = object : Handler(looper) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                DEFAULT_HANDLER(msg) } }
        isRunning = true
        queue.run() }

    override fun commit(step: Message) =
        if (isSynchronized(step))
            synchronize(step) {
                if (queue.run(step, false))
                    step.callback.run() }
        else step.callback.exec()

    private fun RunnableList.run(msg: Message? = null, isIdle: Boolean = true) =
        with(precursorOf(msg)) {
            forEach {
                var ln = it
                synchronized(sLock) {
                    ln = adjust(ln)
                    queue[ln]
                }.exec(isIdle)
                synchronized(sLock) {
                    removeAt(adjust(ln))
                } }
            hasNotTraversed(msg) }

    private fun precursorOf(msg: Message?) = queue.indices

    private fun IntProgression.hasNotTraversed(msg: Message?) = true

    private fun Runnable.exec(isIdle: Boolean = true) {
        if (isIdle && isSynchronized(this))
            synchronize(block = ::run)
        else run() }

    private fun isSynchronized(msg: Message) =
        isSynchronized(msg.callback) ||
        msg.asCallable().isSynchronized()

    private fun isSynchronized(callback: Runnable) =
        getCoroutine(callback).asNullable().isSynchronized() ||
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

    override fun adjust(index: Number) = when (index) {
        is Int -> index
        else -> getEstimatedIndex(index) }

    private fun getEstimatedIndex(delay: Number) = queue.size

    private var sLock = Any()

    override fun attach(step: Runnable, vararg args: Any?) =
        synchronized<Unit>(sLock) { with(queue) {
            add(step)
            markTagsForClkAttach(size, step) } }

    override fun attach(index: Number, step: Runnable, vararg args: Any?) =
        synchronized<Unit>(sLock) {
            queue.add(index.toInt(), step)
            // remark items in queue for adjustment
            markTagsForClkAttach(index, step) }

    var post = fun(callback: Runnable) =
        handler?.post(callback)
        ?: attach(callback)

    var postAhead = fun(callback: Runnable) =
        handler?.postAtFrontOfQueue(callback)
        ?: attach(0, callback)

    fun clearObjects() {
        handler = null
        queue.clear() }

    companion object : RunnableGrid by mutableListOf() {
        private var DEFAULT_HANDLER: HandlerFunction = { commit(it) }

        private fun Clock.register() {
            queue = mutableListOf()
            add(queue) }

        private fun Clock.register(callback: Runnable) {
            queue.add(callback) }

        fun getMessage(step: CoroutineStep): Message? = null

        fun getRunnable(step: CoroutineStep): Runnable? = null

        fun getCoroutine(callback: Runnable): CoroutineStep? = null

        fun getMessage(step: Step): Message? = null

        fun getRunnable(step: Step): Runnable? = null

        fun getStep(callback: Runnable): Step? = null

        fun getEstimatedDelay(step: CoroutineStep): Long? = null

        fun getDelay(step: CoroutineStep): Long? = null

        fun getTime(step: CoroutineStep): Long? = null

        fun getEstimatedDelay(step: Step): Long? = null

        fun getDelay(step: Step): Long? = null

        fun getTime(step: Step): Long? = null

        fun startSafely() = apply {
            if (isNotStarted) start() }

        val isRunning
            get() = clock?.isRunning == true

        fun quit() = clock?.quit()

        fun apply(block: Clock.() -> Unit) = clock?.apply(block)
    }
}

private interface AttachOperator<in S> {
    fun attach(step: S, vararg args: Any?): Any?
}

private interface AdjustOperator<in S, I> : AttachOperator<S> {
    fun attach(index: I, step: S, vararg args: Any?): Any?
    fun adjust(index: I): I
}

private interface PriorityQueue<E> {
    var queue: MutableList<E>
}

interface Transactor<T, out R> {
    fun commit(step: T): R
}

private object HandlerScope : ResolverScope {
    override fun commit(step: AnyCoroutineStep) =
        attach(step, ::handle)

    override fun windDown() {
        Clock.apply {
        Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_DEFAULT) } }
}

@OptIn(ExperimentalCoroutinesApi::class)
object EventBus : AbstractFlow<Any?>(), Transactor<ContextStep, Boolean>, PriorityQueue<Any?> {
    override suspend fun collectSafely(collector: AnyFlowCollector) {
        queue.forEach { event ->
            if (event.canBeCollectedBy(collector))
                collector.emit(event) } }

    private fun Any?.canBeCollectedBy(collector: AnyFlowCollector) = true

    override fun commit(step: ContextStep) =
        queue.add(step)

    fun commit(event: Transit) =
        queue.add(event)

    internal fun commit(vararg event: Event) =
        event.forEach { commit(it.transit) }

    override var queue: MutableList<Any?> = mutableListOf()

    internal fun clear() {
        queue.clear() }
}

fun AnyStep.relay(transit: Transit = this.transit) =
    Relay(transit)

fun AnyStep.reinvoke(transit: Transit = this.transit) =
    object : Relay(transit) {
        override suspend fun invoke() = this@reinvoke() }

open class Relay(val transit: Transit = null) : AnyStep {
    override suspend fun invoke(): Any? = Unit
}

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
@Repeatable
annotation class Event(
    val transit: TransitType = 0) {

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    @Repeatable
    annotation class Listening(
        val channel: ChannelType = 0,
        val timeout: Long = 0L) {

        @Retention(SOURCE)
        @Target(EXPRESSION)
        @Repeatable
        annotation class OnEvent(
            val transit: TransitType)
    }

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    @Repeatable
    annotation class Committing(
        val channel: ChannelType = 0)

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    annotation class Retrying(
        val channel: ChannelType = 0)

    @Retention(SOURCE)
    @Target(FUNCTION, EXPRESSION)
    annotation class Repeating(
        val channel: ChannelType = 0,
        val count: Int = 0)
}

@Retention(SOURCE)
@Target(FUNCTION, EXPRESSION)
annotation class Delay(
    val millis: Long = 0L)

@Retention(SOURCE)
@Target(FUNCTION, EXPRESSION)
annotation class Timeout(
    val millis: Long = -1L)

@Retention(SOURCE)
@Target(FUNCTION, EXPRESSION)
annotation class Pathwise(
    val route: SchedulerPath = [])

private open class Item<T>(override var obj: T) : ObjectReference<T>, CharSequence {
    companion object {
        fun <T> find(ref: Coordinate): T = TODO()

        fun <T> find(target: AnyKClass = Any::class, key: KeyType): T = TODO()

        fun <T, I : Item<T>> Item<T>.reload(obj: T): I = TODO()

        fun <T, I : Item<T>> Item<T>.reload(property: KProperty<T>): I = TODO()

        fun <T, I : Item<T>> Item<T>.reload(tag: String): I = TODO()

        fun <T, I : Item<T>> Item<T>.reload(target: AnyKClass, key: KeyType): I = TODO()
    }

    private val tag: CharSequence
        get() = ::obj.tag?.string
            ?: "${obj.hashCode()}"

    lateinit var type: Type

    enum class Type { Coroutine, JobFunction, LiveStep, SchedulerStep, Step, Work, Runnable, Message, Lock, State }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Item<*>) return other == obj
        if (obj != other.obj) return false
        if (tag != other.tag) return false
        if (type != other.type) return false
        return true }

    override fun hashCode() = obj.hashCode()

    override fun get(index: Int) = tag[index]

    override fun subSequence(startIndex: Int, endIndex: Int) = tag.subSequence(startIndex, endIndex)

    override val length get() = tag.length
}

internal operator fun <T> ResolverScope.get(ref: Coordinate) =
    with(ref) { get<T>(target, key) }

private operator fun <T> ResolverScope.get(target: AnyKClass = Any::class, key: KeyType): T = TODO()

private fun coroutineStep(target: AnyKClass, key: KeyType) =
    SchedulerScope().get<AnyCoroutineStep>(target, key)

private fun liveStep(target: AnyKClass, key: KeyType) =
    SchedulerScope().get<SequencerStep>(target, key)

private fun step(target: AnyKClass, key: KeyType) =
    SchedulerScope().get<AnyStep>(target, key)

private fun runnable(target: AnyKClass, key: KeyType) =
    SchedulerScope().get<Runnable>(target, key)

private class Node<T>(override var obj: T) : Item<T>(obj), KCallable<T> {
    lateinit var visitor: Visitor<T>

    override fun call(vararg args: Any?): T = ::obj.call(*args)

    override fun callBy(args: Map<KParameter, Any?>) =
        call(*parameters.map(args::get).toTypedArray())

    override val annotations = ::obj.annotations
    override val isAbstract = ::obj.isAbstract
    override val isFinal = ::obj.isFinal
    override val isOpen = ::obj.isOpen
    override val isSuspend = ::obj.isSuspend
    override val name = ::obj.name
    override val parameters = ::obj.parameters
    override val returnType = ::obj.returnType
    override val typeParameters = ::obj.typeParameters
    override val visibility = ::obj.visibility
}

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CLASS, CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Coordinate(
    val target: AnyKClass = Any::class,
    val key: KeyType = 0)

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Tag(
    val string: String,
    val keep: Boolean = true)

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
internal annotation class Keep

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
@Repeatable
annotation class Path(
    val name: String = "",
    val route: SchedulerPath = []) {

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Adjacent(
        val paths: StringArray = [])

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Preceding(
        val paths: StringArray = [])

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Proceeding(
        val paths: StringArray = [])

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Parallel(
        val paths: StringArray = [])

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Diverging(
        val paths: StringArray = [])

    @Retention(SOURCE)
    @Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
    @Repeatable
    annotation class Converging(
        val paths: StringArray = [])
}

open class SchedulerIntent : Throwable()

internal open class Propagate : SchedulerIntent()

abstract class FromLastCancellation : SchedulerIntent()

@Retention(SOURCE)
@Target(EXPRESSION)
annotation class WithContext

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
internal annotation class JobTree(
    val branch: String = "",
    val level: LevelType = 0u)

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class JobTreeRoot

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
internal annotation class Scope(
    val type: KClass<out CoroutineScope> = Scheduler::class,
    val provider: AnyKClass = Any::class)

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class LaunchScope

@Retention(SOURCE)
@Target(ANNOTATION_CLASS, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
private annotation class Synchronous(
    val node: SchedulerNode = Annotation::class)

@Retention(SOURCE)
@Target(EXPRESSION)
annotation class Ahead

@Retention(SOURCE)
@Target(EXPRESSION)
annotation class Implicit

@Retention(SOURCE)
@Target(EXPRESSION)
private annotation class Enlisted

@Retention(SOURCE)
@Target(EXPRESSION)
private annotation class Unlisted

private val AnyKCallable.tag
    get() = annotations.find { it is Tag } as? Tag

internal val Any.isKept
    get() = asCallable().annotations.any { it is Keep }

private val AnyKCallable.event
    get() = annotations.find { it is Event } as? Event

private val AnyKCallable.events
    get() = annotations.filterIsInstance<Event>()

private val AnyKCallable.schedulerScope
    get() = annotations.find { it is Scope } as? Scope

private val AnyKCallable.launchScope
    get() = annotations.find { it is LaunchScope } as? LaunchScope

private val Any.annotatedScope
    get() = trySafelyForResult { asCallable().schedulerScope!!.let { annotation ->
        when (annotation.provider) {
            Any::class ->
                annotation.type.reconstruct(this)
            Activity::class,
            Fragment::class ->
                foregroundLifecycleOwner.asObjectProvider()!!(annotation.type)
            else ->
                throw BaseImplementationRestriction()
        } as CoroutineScope } }

private fun Any.annotatedOrSchedulerScope() = annotatedScope ?: SchedulerScope()

internal val AnyStep.isScheduledAhead
    get() = asCallable().annotations.find { it is Ahead } !== null

internal val Any.isImplicit
    get() = asCallable().annotations.find { it is Implicit } !== null

private val Any.isEnlisted
    get() = asCallable().annotations.find { it is Enlisted } !== null

private val Any.isUnlisted
    get() = asCallable().annotations.find { it is Unlisted } !== null

private typealias PropertyCondition = suspend (AnyKProperty, String, AnyStep) -> Any?
private typealias PropertyPredicate = suspend (AnyKProperty) -> Boolean
private typealias PropertyState = suspend (AnyKProperty) -> Any?

private typealias PredicateFunction = suspend () -> Boolean
private typealias DelayFunction = suspend () -> Long

internal interface Expiry : MutableSet<Lifetime> {
    fun unsetAll(property: AnyKMutableProperty) {
        // must be strengthened by connecting to other expiry sets
        forEach { alive ->
            if (alive(property) == false && State.of(property) !== Lock.Closed)
                property.expire()
    } }
    companion object : Expiry {
        override fun add(element: Lifetime) = false
        override fun addAll(elements: Collection<Lifetime>) = false
        override fun clear() {}
        override fun iterator(): MutableIterator<Lifetime> = TODO()
        override fun remove(element: Lifetime): Boolean = false
        override fun removeAll(elements: Collection<Lifetime>) = false
        override fun retainAll(elements: Collection<Lifetime>) = false
        override fun contains(element: Lifetime) = false
        override fun containsAll(elements: Collection<Lifetime>) = false
        override fun isEmpty() = true
        override val size: Int
            get() = 0
    }
}

private typealias Lifetime = (AnyKMutableProperty) -> Boolean?

fun AnyKMutableProperty.expire() = set(null)

internal fun <T> T.asCallable(): KCallable<T> = asObjRef()::obj
internal fun <T> T?.asNullable(): KCallable<T?> = asNullRef()::obj

private interface ObjectReference<T> { var obj: T }
private interface NullReference<T> : ObjectReference<T?>

private fun <T> T.asObjRef() =
    asUniqueRef { object : ObjectReference<T> {
        override var obj = this@asObjRef } }

private fun <T> T?.asNullRef() =
    asUniqueRef { object : NullReference<T> {
        override var obj = this@asNullRef } }

private inline fun <T> T.asUniqueRef(block: () -> ObjectReference<T>) =
    if (this is ObjectReference<*>)
        this::obj.asType()!!
    else block()

internal fun trueWhenNull(it: Any?) = it === null

private typealias TransitType = Short
private typealias Transit = TransitType?

internal val Any?.transit: Transit
    get() = when (this) {
        is Relay -> transit
        is Number -> toTransit()
        else -> asNullable().event?.transit }

internal fun Number?.toTransit() = this?.asType<TransitType>()

private typealias KeyType = Short

internal fun Number?.toCoordinateTarget(): AnyKClass = Any::class

internal fun Number?.toCoordinateKey() = this?.asType<KeyType>()

internal typealias ChannelType = Short

internal fun Number?.toChannel() = this?.asType<ChannelType>()

internal fun Array<out Tag>.mapToStringArray() = mapToTypedArray { it.string }

internal fun Array<out Path>.mapToStringArray() = mapToTypedArray { it.name }

internal typealias LevelType = UByte

internal fun Number?.toLevel() = this?.toByte()?.toUByte()

internal fun Any?.asMessage() = asType<Message>()
internal fun Any?.asRunnable() = asType<Runnable>()
internal fun Any?.asLiveWork() = asType<LiveWork>()
internal fun Any?.asJob() = asType<Job>()
internal fun Any?.asWork() = asType<Work>()

private typealias SchedulerNode = KClass<out Annotation>
private typealias SchedulerPath = Array<KClass<out Throwable>>
private typealias SchedulerStep = suspend CoroutineScope.(Job, Any?) -> Unit
internal typealias JobFunction = suspend (Any?) -> Unit
private typealias JobFunctionSet = MutableSet<JobFunctionItem>
private typealias JobFunctionItem = CharsToAnyPair
private typealias JobPredicate = (Job) -> Boolean
internal typealias CoroutineFunction = (CoroutineStep) -> Any?
internal typealias AnyCoroutineFunction = (AnyCoroutineStep) -> Any?
private typealias CoroutinePointer = () -> CoroutineStep?
private typealias AnyCoroutinePointer = () -> AnyCoroutineStep?
private typealias CharsToAnyPair = Pair<CharsPointer, Any>

private typealias SequencerScope = LiveDataScope<Step?>
private typealias SequencerStep = suspend SequencerScope.(Any?) -> Unit
private typealias StepObserver = Observer<Step?>
private typealias AnyStepObserver = Observer<AnyStep?>
private typealias LiveStep = LiveData<Step?>
private typealias LiveStepPointer = () -> LiveStep?
private typealias CaptureFunction = AnyToAnyFunction
private typealias LiveWork = Triple<LiveStepPointer, CaptureFunction?, Boolean>
private typealias LiveSequence = MutableList<LiveWork>
private typealias LiveWorkFunction = (LiveWork) -> Any?
private typealias LiveWorkPredicate = (LiveWork) -> Boolean
private typealias StepFunction = (Step) -> Any?
private typealias AnyStepFunction = (AnyStep) -> Any?
private typealias StepPointer = () -> Step

private typealias HandlerFunction = Clock.(Message) -> Unit
private typealias MessageFunction = (Message) -> Any?
private typealias MessagePredicate = (Message) -> Boolean
private typealias RunnableFunction = (Runnable) -> Any?
private typealias RunnablePredicate = (Runnable) -> Boolean
private typealias RunnableList = MutableList<Runnable>
private typealias RunnableGrid = MutableList<RunnableList>

private typealias StatePredicate = (Any, Any?) -> Boolean

typealias Work = () -> Unit
internal typealias Step = suspend () -> Unit
internal typealias AnyStep = suspend () -> Any?
internal typealias AnyToAnyStep = suspend (Any?) -> Any?
internal typealias CoroutineStep = suspend CoroutineScope.() -> Unit
internal typealias AnyCoroutineStep = suspend CoroutineScope.() -> Any?
private typealias AnyFlowCollector = FlowCollector<Any?>

internal typealias ExceptionHandler = Thread.UncaughtExceptionHandler
internal typealias Process = android.os.Process

val emptyWork = {}
val emptyStep = suspend {}

internal val processLifecycleScope
    get() = ProcessLifecycleOwner.get().lifecycleScope

internal val currentThread get() = Thread.currentThread()
internal val mainThread = currentThread
internal val onMainThread get() = currentThread.isMainThread()
fun Thread.isMainThread() = this === mainThread

private fun newThread(group: ThreadGroup, name: String, priority: Int, target: Runnable) = Thread(group, target, name).also { it.priority = priority }
private fun newThread(name: String, priority: Int, target: Runnable) = Thread(target, name).also { it.priority = priority }
private fun newThread(priority: Int, target: Runnable) = Thread(target).also { it.priority = priority }

internal fun Context.registerReceiver(filter: IntentFilter) =
    registerReceiver(this, receiver, filter, null,
        clock?.alsoStartAsync()?.handler,
        RECEIVER_EXPORTED)

private typealias JobKFunction = KFunction<Job?>
private typealias JobKProperty = KMutableProperty<Job?>
private typealias ResolverKClass = KClass<out Resolver>
private typealias ResolverKProperty = KMutableProperty<out Resolver?>
private typealias UnitKFunction = KFunction<Unit>

typealias AnyKClass = KClass<*>
internal typealias AnyKCallable = KCallable<*>
typealias AnyKFunction = KFunction<*>
internal typealias AnyKProperty = KProperty<*>
internal typealias AnyKMutableProperty = KMutableProperty<*>

private operator fun AnyKCallable.plus(lock: AnyKCallable) = this

private fun <R> AnyKCallable.synchronize(block: () -> R) = synchronized(this, block)

private interface Synchronizer<L> {
    fun <R> synchronize(lock: L? = null, block: () -> R): R
}

enum class Lock : State { Closed, Open }

private typealias ID = Short

internal fun Number?.toStateId() = this?.asType<ID>()

sealed interface State {
    data object Succeeded : Resolved
    data object Failed : Resolved

    sealed interface Resolved : State {
        companion object : Resolved {
            inline infix fun where(predicate: Predicate) =
                if (predicate()) this
                else Unresolved

            inline infix fun unless(predicate: Predicate) =
                if (predicate()) Unresolved
                else this
    } }

    sealed interface Unresolved : State { companion object : Unresolved }
    sealed interface Ambiguous : State { companion object : Ambiguous }

    companion object : Synchronizer<State> {
        fun of(vararg args: Any?): State = Ambiguous

        internal fun of(property: AnyKProperty): State = Ambiguous

        operator fun <R> invoke(block: Companion.(State) -> R): State = TODO()

        fun State.register(vararg args: Any?): State = TODO()

        fun <R> State.onStateChanged(value: State, block: Companion.(State) -> R): R = TODO()

        override fun <R> synchronize(lock: State?, block: () -> R) =
            synchronized(lock ?: this, block)

        internal operator fun invoke(): State = Lock.Open

        operator fun get(id: ID): State = when (id.toInt()) {
            1 -> Resolved unless ::isAppDbOrSessionNull
            2 -> Resolved unless ::isLogDbOrNetDbNull
            else ->
                Lock.Open
        }
        operator fun set(id: ID, lock: Any) { when (id.toInt()) {
            1 -> if (lock is Resolved) SchedulerScope().windDown()
        } }

        internal operator fun plus(lock: Any): State = Ambiguous
        internal operator fun plusAssign(lock: Any) {}
        internal operator fun minus(lock: Any): State = Ambiguous
        internal operator fun minusAssign(lock: Any) {}
        internal operator fun rangeTo(lock: Any): State = Ambiguous
        internal operator fun not(): State = Ambiguous
        internal operator fun contains(lock: Any) = false
        internal operator fun compareTo(lock: Any) = 1
    }

    operator fun invoke(vararg param: Any?): Lock = this as Lock
    operator fun get(id: ID) = this
    operator fun set(id: ID, state: Any) = Unit
    operator fun plus(state: Any) = this
    operator fun minus(state: Any) = this
    operator fun rangeTo(state: Any) = this
    operator fun not(): State = this
    operator fun contains(state: Any) = state === this
    operator fun compareTo(state: Any) = 0
}

internal val SVC_TAG get() = if (onMainThread) "SERVICE" else "CLOCK"