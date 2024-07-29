package net.consolator

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import net.consolator.application.MemoryManager
import net.consolator.Scheduler.applicationMemoryManager

open class MainActivity : BaseActivity(), ObjectProvider {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<MainFragment>(containerViewId)
                setVisible(false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        defer<ConfigurationChangeManager>(::onConfigurationChanged, newConfig)
        @Implicit {
            super.onConfigurationChanged(newConfig) }
    }

    override fun onNightModeChanged(mode: Int) {
        defer<NightModeChangeManager>(::onNightModeChanged, mode)
        @Implicit {
            super.onNightModeChanged(mode) }
    }

    override fun onLocalesChanged(locales: LocaleListCompat) {
        defer<LocalesChangeManager>(::onLocalesChanged, locales)
        @Implicit {
            super.onLocalesChanged(locales) }
    }

    internal inner class ConfigurationChangeManager : BaseActivity.ConfigurationChangeManager()
    internal inner class NightModeChangeManager : BaseActivity.NightModeChangeManager()
    internal inner class LocalesChangeManager : BaseActivity.LocalesChangeManager()

    override fun invoke(type: AnyKClass): Resolver = when (type) {
        ConfigurationChangeManager::class ->
            ConfigurationChangeManager()
        NightModeChangeManager::class ->
            NightModeChangeManager()
        LocalesChangeManager::class ->
            LocalesChangeManager()
        else ->
            throw BaseImplementationRestriction() }
}