package com.aliceclais.realmep3litetweaker.xposed

import android.view.View
import com.aliceclais.realmep3litetweaker.PrefsManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method

/**
 * ИСПРАВЛЕНИЕ: в этой сборке лаунчера запуск с иконки идёт через
 * ActivityContext.startActivitySafely -> makeDefaultActivityOptions (QuickstepLauncher),
 * который делает обычный makeCustomAnimation, а настоящую анимацию
 * (QuickstepTransitionManager.getActivityLaunchOptions) не вызывает.
 *
 * Хук: пока идёт QuickstepLauncher.startActivitySafely(view, intent, item),
 * подменяем результат makeDefaultActivityOptions на QuickstepLauncher.getActivityLaunchOptions(view, item).
 *
 * Выключатель: файл /data/local/tmp/disable_p3_anim. Любая ошибка -> остаётся оригинал.
 */
class AnimationHook : IXposedHookLoadPackage {

    private val tag = "TweakTheP3Lite"
    private val DEFAULT_SLOW = 1.8f
    private val pending = ThreadLocal<Array<Any?>>()
    private val busy = ThreadLocal<Boolean>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher3") return
        if (runCatching { File("/data/local/tmp/disable_p3_anim").exists() }.getOrDefault(false)) {
            XposedBridge.log("$tag: kill-switch active")
            return
        }
        runCatching {
            val prefs = XSharedPreferences("com.aliceclais.realmep3litetweaker", PrefsManager.PREFS_NAME)
            prefs.reload()
            if (!prefs.getBoolean(PrefsManager.KEY_ENABLE_ANIMATIONS, true)) {
                XposedBridge.log("$tag: disabled in settings")
                return
            }
        }

        runCatching { install(lpparam.classLoader) }
            .onFailure { XposedBridge.log("$tag: install failed: $it") }
    }

    private fun install(cl: ClassLoader) {
        val ql = XposedHelpers.findClassIfExists(
            "com.android.launcher3.uioverrides.QuickstepLauncher", cl
        ) ?: run { XposedBridge.log("$tag: QuickstepLauncher not found"); return }

        val getOpts: Method = ql.declaredMethods.firstOrNull {
            it.name == "getActivityLaunchOptions" && !it.isBridge && !it.isSynthetic &&
                it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java
        }?.apply { isAccessible = true }
            ?: run { XposedBridge.log("$tag: getActivityLaunchOptions not found"); return }

        // 1) запоминаем view/item на время запуска с иконки
        var hooked = 0
        ql.declaredMethods.filter { it.name == "startActivitySafely" && it.parameterTypes.size == 3 }
            .forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        pending.set(arrayOf(p.args[0], p.args[2]))
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        pending.remove()
                    }
                })
                hooked++
            }

        // 2) подменяем простые опции на настоящие опции анимации
        ql.declaredMethods.filter {
            it.name == "makeDefaultActivityOptions" && it.returnType.simpleName == "ActivityOptionsWrapper"
        }.forEach { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val ctx = pending.get() ?: return
                    if (busy.get() == true) return
                    val view = ctx[0] as? View ?: return
                    busy.set(true)
                    try {
                        val r = getOpts.invoke(p.thisObject, view, ctx[1])
                        if (r != null) {
                            p.result = r
                            XposedBridge.log("$tag: applied launch animation for icon")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$tag: fix skipped: $t")
                    } finally {
                        busy.remove()
                    }
                }
            })
            hooked++
        }
        // 3) скорость: растягиваем длительность открытия (Unisoc отдаёт слишком короткую)
        val scale = runCatching {
            val prefs = XSharedPreferences("com.aliceclais.realmep3litetweaker", PrefsManager.PREFS_NAME)
            prefs.reload()
            prefs.getFloat(PrefsManager.KEY_ANIM_SPEED_SCALE, DEFAULT_SLOW).coerceIn(0.5f, 3f)
        }.getOrDefault(DEFAULT_SLOW)
        XposedHelpers.findClassIfExists("com.android.launcher3.QuickstepTransitionManager", cl)
            ?.declaredMethods?.filter { it.name == "getLaunchAnimDurationByUniPnP" }?.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val d = p.result as? Long ?: return
                        p.result = (d * scale).toLong()
                        XposedBridge.log("$tag: launch duration $d -> ${p.result} (x$scale)")
                    }
                })
                hooked++
            }

        // 4) только лог: что происходит при закрытии и какие длительности используются
        fun short(o: Any?): String = try { o.toString().take(120) } catch (_: Throwable) { "?" }
        val watch = mapOf(
            "com.android.launcher3.QuickstepTransitionManager" to setOf(
                "getClosingWindowAnimators", "getFallbackClosingWindowAnimators",
                "shouldPlayFallbackClosingAnimation", "getOpeningWindowAnimators",
                "createWallpaperOpenAnimations", "getAppToHomeLightAnimators"
            ),
            "com.android.launcher3.QuickstepTransitionManager\$WallpaperOpenLauncherAnimationRunner" to null
        )
        for ((cn, names) in watch) {
            val c = XposedHelpers.findClassIfExists(cn, cl) ?: continue
            c.declaredMethods.filter { (names == null || it.name in names) && !it.name.startsWith("lambda$") }.forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            XposedBridge.log("$tag: [W] >> ${c.simpleName}.${m.name}")
                        }
                        override fun afterHookedMethod(p: MethodHookParam) {
                            XposedBridge.log("$tag: [W] << ${c.simpleName}.${m.name} = ${short(p.result)}")
                        }
                    })
                }
            }
        }
        XposedBridge.log("$tag: installed, hooks=$hooked, scale=$scale")
    }
}
