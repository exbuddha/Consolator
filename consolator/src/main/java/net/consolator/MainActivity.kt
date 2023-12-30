package net.consolator

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import kotlin.reflect.KClass

open class MainActivity : BaseActivity(), ObjectProvider {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<MainFragment>(containerViewId)
                setVisible(false)
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        (this as Context).defer<ConfigurationChangeManager>(::onConfigurationChanged, newConfig) {
            super.onConfigurationChanged(newConfig)
        }
    }

    override fun onNightModeChanged(mode: Int) {
        (this as Context).defer<NightModeChangeManager>(::onNightModeChanged, mode) {
            super.onNightModeChanged(mode)
        }
    }

    override fun onLocalesChanged(locales: LocaleListCompat) {
        (this as Context).defer<LocalesChangeManager>(::onLocalesChanged, locales) {
            super.onLocalesChanged(locales)
        }
    }

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