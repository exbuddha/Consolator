@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

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

@Tag(MAIN_ACTIVITY)
open class MainActivity : BaseActivity(), Provider {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<MainFragment>(containerViewId)
                setVisible(false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        defer<iso.consolator.activity.ConfigurationChangeManager>(
            ::onConfigurationChanged, newConfig)
        @Ahead {
            super.onConfigurationChanged(newConfig) }
    }

    override fun onNightModeChanged(mode: Int) {
        defer<iso.consolator.activity.NightModeChangeManager>(
            ::onNightModeChanged, mode)
        @Ahead {
            super.onNightModeChanged(mode) }
    }

    override fun onLocalesChanged(locales: LocaleListCompat) {
        defer<iso.consolator.activity.LocalesChangeManager>(
            ::onLocalesChanged, locales)
        @Ahead {
            super.onLocalesChanged(locales) }
    }

    override fun commit(vararg context: Any?) =
        context.lastOrNull()?.asTransitFunction()?.invoke(this)

    internal inner class ConfigurationChangeManager : BaseActivity.ConfigurationChangeManager()
    internal inner class NightModeChangeManager : BaseActivity.NightModeChangeManager()
    internal inner class LocalesChangeManager : BaseActivity.LocalesChangeManager()

    override fun invoke(type: AnyKClass): Resolver = when (type) {
        iso.consolator.activity.ConfigurationChangeManager::class ->
            ConfigurationChangeManager()
        iso.consolator.activity.NightModeChangeManager::class ->
            NightModeChangeManager()
        iso.consolator.activity.LocalesChangeManager::class ->
            LocalesChangeManager()
        else ->
            throw BaseImplementationRestriction() }

    override fun <R> invoke(vararg tag: TagType): KCallable<R> = TODO()
}