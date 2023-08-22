package backgammon.module

import android.content.Context
import android.os.*
import android.util.*
import android.view.*
import androidx.core.util.component1
import androidx.core.util.component2
import androidx.fragment.app.*
import androidx.fragment.app.FragmentTransaction.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.Scheduler.Event.Listening
import backgammon.module.Scheduler.EventBus
import backgammon.module.Scheduler.defer
import backgammon.module.BaseApplication.Companion.ACTION_NAV_MAIN_UI
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP
import backgammon.module.application.*

abstract class BaseFragment : Fragment() {
    private val viewModel
        get() = activity?.asType<BaseActivity>()?.viewModel

    protected abstract var navigate: (View, Bundle?) -> Pair<Fragment, Int?>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launch @MainViewGroup @Listening {
            EventBus.collectSafely {
                when (it?.transit) {
                    ACTION_NAV_MAIN_UI -> {
                        schedule {
                            parentFragmentManager.commit {
                                val (overlay, transition) =
                                    navigate(view, savedInstanceState?.apply {
                                        putShort(ACTION_KEY, ACTION_NAV_MAIN_UI)
                                    })
                                setTransition(transition ?: TRANSIT_FRAGMENT_OPEN)
                                replace(
                                    this@BaseFragment.id,
                                    overlay)
                            }
                        }
                        close(MainViewGroup::class)
                    }
                    ACTION_MIGRATE_APP ->
                        defer(::onViewCreated, Migration::class)
                }
            }
        } onCancel {
            // handle listening error
            State[1] = State.Failed
        }
        if (infoLogIsNotBypassed)
            info(UI_TAG, "Main fragment view is created.")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        launch(Dispatchers.IO) @JobTreeRoot @MainViewGroup {
            trySafelyCanceling {
                with(context) {
                    buildAppDatabase()
                    event(Context::stageDbCreated)
                    if (session === null)
                        session = tryCancelingForResult {
                            runtimeDao {
                                getSession(
                                    newSession(instance!!.startTime))
                            }
                        }
                    event(Context::stageSessionCreated)
                }
            }
        }
    }

    override fun onDestroyView() {
        close(MainViewGroup::class)
        super.onDestroyView()
    }

    override fun onLowMemory() {
        defer(::onLowMemory, MemoryManager::class, { super.onLowMemory() })
    }

    @Retention(SOURCE)
    @Target(EXPRESSION)
    protected annotation class MainViewGroup

    companion object {
        const val UI_TAG = "FRAGMENT"
    }
}