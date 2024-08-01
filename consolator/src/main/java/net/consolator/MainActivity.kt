package net.consolator

import android.content.res.*
import android.os.*
import androidx.annotation.*
import androidx.core.os.*
import androidx.fragment.app.*
import iso.consolator.*
import kotlin.reflect.*

@IdRes
internal var containerViewId = R.id.layout_background

internal open class MainActivity : BaseActivity(), ObjectProvider, FunctionProvider {
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

    inner class ConfigurationChangeManager : BaseActivity.ConfigurationChangeManager()
    inner class NightModeChangeManager : BaseActivity.NightModeChangeManager()
    inner class LocalesChangeManager : BaseActivity.LocalesChangeManager()

    override fun invoke(type: AnyKClass): Resolver = when (type) {
        ConfigurationChangeManager::class ->
            ConfigurationChangeManager()
        NightModeChangeManager::class ->
            NightModeChangeManager()
        LocalesChangeManager::class ->
            LocalesChangeManager()
        else ->
            throw BaseImplementationRestriction() }

    override fun <R> invoke(vararg tag: TagType): KCallable<R> = TODO()
}