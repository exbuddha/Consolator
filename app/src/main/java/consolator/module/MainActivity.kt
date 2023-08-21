package consolator.module

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import kotlin.reflect.KClass
import backgammon.module.BaseActivity
import backgammon.module.BaseImplementationRestriction
import backgammon.module.Provider
import backgammon.module.defer
import backgammon.module.step

open class MainActivity : BaseActivity(), Provider {
    override val backgroundLayoutResId = R.layout.background
    override val viewModel: VM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<MainFragment>(R.id.layout_background)
                setVisible(false)
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        defer<ConfigurationChangeManager>(::onConfigurationChanged, newConfig)
        { super.onConfigurationChanged(newConfig) } ?:
        step<ConfigurationChangeManager>(newConfig) {
            // ...
        }
    }

    override fun onNightModeChanged(mode: Int) {
        defer<NightModeChangeManager>(::onNightModeChanged, mode)
        { super.onNightModeChanged(mode) } ?:
        step<NightModeChangeManager>(mode) {
            // ...
        }
    }

    override fun onLocalesChanged(locales: LocaleListCompat) {
        defer<LocalesChangeManager>(::onLocalesChanged, locales)
        { super.onLocalesChanged(locales) } ?:
        step<LocalesChangeManager>(locales) {
            // ...
        }
    }

    class VM : BaseActivity.VM()

    inner class ConfigurationChangeManager : BaseActivity.ConfigurationChangeManager()
    inner class NightModeChangeManager : BaseActivity.NightModeChangeManager()
    inner class LocalesChangeManager : BaseActivity.LocalesChangeManager()

    override fun invoke(type: KClass<*>) = when (type) {
        ConfigurationChangeManager::class ->
            ConfigurationChangeManager()
        NightModeChangeManager::class ->
            NightModeChangeManager()
        LocalesChangeManager::class ->
            LocalesChangeManager()
        else ->
            throw BaseImplementationRestriction
    }
}