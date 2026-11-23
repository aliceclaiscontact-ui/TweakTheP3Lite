package com.aliceclais.realmep3litetweaker.xposed

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * TweakTheP3Lite: анимации лаунчера realme P3 Lite (com.android.launcher3).
 *
 * 1) Запуск с иконки: ActivityContext.startActivitySafely зовёт QuickstepLauncher.makeDefaultActivityOptions
 *    (обычный makeCustomAnimation). Подменяем результат на QuickstepLauncher.getActivityLaunchOptions(view, item)
 *    -> настоящая remote-анимация + launch cookie (нужен для возврата в иконку).
 * 2) Тёплый старт (задача приложения уже есть): та же анимация окна из иконки, но плавающую иконку
 *    (FloatingIconView) скрываем -- не растягивается на весь экран поверх окна.
 * 3) Закрытие (кнопка/жест "назад" и "домой" через переход QuickstepLaunchHome): WallpaperOpenLauncherAnimationRunner
 *    зовёт createWallpaperOpenAnimations(..., fromBack=false) -> только "лёгкая" анимация. Если иконка приложения
 *    найдена, передаём fromBack=true -> окно сворачивается в иконку (getClosingWindowAnimators).
 * 3b) Свайп вверх "домой": в этой сборке LauncherSwipeHandlerV2.createHomeAnimationFactory ВСЕГДА возвращает
 *    обычную фабрику ($2) -- вызов createIconHomeAnimationFactory вырезан (метод остался, но не вызывается).
 *    Возвращаем его: если иконка приложения найдена на рабочем столе, окно сворачивается в иконку.
 * 4) Квадратные иконки: радиус окна и радиус плавающей иконки = 0.
 *
 * Скорость: системный масштаб анимаций. Аварийный выключатель: файл /data/local/tmp/disable_p3_anim.
 * Любая ошибка -> остаётся оригинальное поведение.
 */
class AnimationHook : IXposedHookLoadPackage {

    private val tag = "TweakTheP3Lite"
    private val pending = ThreadLocal<Array<Any?>>()
    private val busy = ThreadLocal<Boolean>()
    private val inRunner = ThreadLocal<Boolean>()
    @Volatile private var warmUntil = 0L

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
        val cl = lpparam.classLoader
        runCatching { installLaunchFix(cl) }.onFailure { XposedBridge.log("$tag: launch fix failed: $it") }
        runCatching { installFloatingIcon(cl) }.onFailure { XposedBridge.log("$tag: floating icon failed: $it") }
        runCatching { installIconClose(cl) }.onFailure { XposedBridge.log("$tag: icon close failed: $it") }
        runCatching { installSwipeHome(cl) }.onFailure { XposedBridge.log("$tag: swipe home failed: $it") }
    }

    // ---------- помощники ----------

    private fun packageOf(intent: Intent?, item: Any?): String? {
        intent?.component?.packageName?.let { return it }
        intent?.`package`?.let { return it }
        if (item != null) {
            runCatching {
                (XposedHelpers.callMethod(item, "getTargetComponent") as? ComponentName)?.packageName
                    ?.let { return it }
            }
        }
        return null
    }

    /** Есть ли уже запущенная задача этого приложения (тёплый старт). При любой ошибке -> false (холодный). */
    @Suppress("DEPRECATION")
    private fun isWarm(ctx: Context, pkg: String): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningTasks(50).any {
            it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg
        }
    }.getOrDefault(false)

    // ---------- 1) запуск с иконки ----------

    private fun installLaunchFix(cl: ClassLoader) {
        val ql = XposedHelpers.findClassIfExists(
            "com.android.launcher3.uioverrides.QuickstepLauncher", cl
        ) ?: run { XposedBridge.log("$tag: QuickstepLauncher not found"); return }

        val getOpts: Method = ql.declaredMethods.firstOrNull {
            it.name == "getActivityLaunchOptions" && !it.isBridge && !it.isSynthetic &&
                it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java
        }?.apply { isAccessible = true }
            ?: run { XposedBridge.log("$tag: getActivityLaunchOptions not found"); return }

        var hooked = 0
        ql.declaredMethods.filter { it.name == "startActivitySafely" && it.parameterTypes.size == 3 }
            .forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        pending.set(arrayOf(p.args[0], p.args[2]))
                        val pkg = packageOf(p.args[1] as? Intent, p.args[2])
                        val warm = pkg != null && (p.thisObject as? Context)?.let { isWarm(it, pkg) } == true
                        warmUntil = if (warm) System.currentTimeMillis() + 3000 else 0L
                        if (warm) XposedBridge.log("$tag: warm start $pkg")
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        pending.remove()
                    }
                })
                hooked++
            }

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
                        if (r != null) p.result = r
                    } catch (t: Throwable) {
                        XposedBridge.log("$tag: launch fix skipped: $t")
                    } finally {
                        busy.remove()
                    }
                }
            })
            hooked++
        }
        XposedBridge.log("$tag: launch fix installed, hooks=$hooked")
    }

    // ---------- 2) плавающая иконка: квадратные углы + скрыть при тёплом старте ----------

    private fun installFloatingIcon(cl: ClassLoader) {
        var hooked = 0

        // радиус скругления окна приложения во время анимации
        XposedHelpers.findClassIfExists("com.android.systemui.shared.system.QuickStepContract", cl)
            ?.declaredMethods?.filter { it.name == "getWindowCornerRadius" && it.returnType == Float::class.javaPrimitiveType }
            ?.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) { p.result = 0f }
                })
                hooked++
            }

        // FloatingIconView.update(alpha, rect, progress, shapeStart, cornerRadius, isOpening[, fgIconAlpha])
        XposedHelpers.findClassIfExists("com.android.launcher3.views.FloatingIconView", cl)
            ?.declaredMethods?.filter { it.name == "update" }
            ?.forEach { m ->
                val floatIdx = m.parameterTypes.indexOfLast { it == Float::class.javaPrimitiveType }
                val boolIdx = m.parameterTypes.indexOfFirst { it == Boolean::class.javaPrimitiveType }
                if (floatIdx < 0) return@forEach
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    private fun hideNow(p: MethodHookParam): Boolean =
                        System.currentTimeMillis() < warmUntil &&
                            (boolIdx < 0 || p.args[boolIdx] == true)

                    override fun beforeHookedMethod(p: MethodHookParam) {
                        p.args[floatIdx] = 0f                       // квадратные углы
                        if (hideNow(p)) p.args[0] = 0f              // тёплый старт: иконку не рисуем
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (hideNow(p)) (p.thisObject as? View)?.alpha = 0f
                    }
                })
                hooked++
            }
        XposedBridge.log("$tag: floating icon hooks=$hooked")
    }

    // ---------- 3b) свайп "домой" -> в иконку ----------

    private fun installSwipeHome(cl: ClassLoader) {
        val h = XposedHelpers.findClassIfExists("com.android.quickstep.LauncherSwipeHandlerV2", cl) ?: return
        val create = h.declaredMethods.firstOrNull {
            it.name == "createHomeAnimationFactory" && it.parameterTypes.size == 6
        } ?: run { XposedBridge.log("$tag: createHomeAnimationFactory not found"); return }
        val findWs = h.declaredMethods.firstOrNull { it.name == "findWorkspaceView" && it.parameterTypes.size == 2 }
            ?.apply { isAccessible = true } ?: return
        val iconFactory = h.declaredMethods.firstOrNull { it.name == "createIconHomeAnimationFactory" && it.parameterTypes.size == 2 }
            ?.apply { isAccessible = true } ?: return

        XposedBridge.hookMethod(create, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val res = p.result ?: return
                    if (!res.javaClass.name.endsWith("LauncherSwipeHandlerV2\$2")) return   // уже не стандартная
                    if (XposedHelpers.getObjectField(p.thisObject, "mContainer") == null) return
                    val isTranslucent = p.args[2] as? Boolean ?: false
                    val canEnterPip = p.args[3] as? Boolean ?: false
                    if (isTranslucent || canEnterPip) return

                    var taskView = p.args[5]
                    if (taskView == null) {
                        val rv = XposedHelpers.getObjectField(p.thisObject, "mRecentsView")
                        taskView = rv?.let { XposedHelpers.callMethod(it, "getRunningTaskView") }
                    }
                    val cookies: Any? = if (p.args[5] == null) p.args[0] else emptyList<Any>()
                    val ws = findWs.invoke(p.thisObject, cookies, taskView) as? View ?: return
                    if (!ws.isAttachedToWindow || ws.height <= 0) return

                    val f = iconFactory.invoke(p.thisObject, ws, taskView) ?: return
                    p.result = f
                    XposedBridge.log("$tag: swipe home -> icon")
                } catch (t: Throwable) {
                    XposedBridge.log("$tag: swipe home skipped: $t")
                }
            }
        })
        XposedBridge.log("$tag: swipe home hook installed")
    }

    // ---------- 3) закрытие в иконку ----------

    private fun installIconClose(cl: ClassLoader) {
        val qtm = XposedHelpers.findClassIfExists("com.android.launcher3.QuickstepTransitionManager", cl) ?: return
        val runner = XposedHelpers.findClassIfExists(
            "com.android.launcher3.QuickstepTransitionManager\$WallpaperOpenLauncherAnimationRunner", cl
        ) ?: return

        val findView: Method = qtm.declaredMethods.firstOrNull {
            it.name == "findLauncherView" && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
        }?.apply { isAccessible = true } ?: run { XposedBridge.log("$tag: findLauncherView not found"); return }

        var hooked = 0
        runner.declaredMethods.filter { it.name == "onAnimationStart" && !it.isBridge && !it.isSynthetic }
            .forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) { inRunner.set(true) }
                    override fun afterHookedMethod(p: MethodHookParam) { inRunner.remove() }
                })
                hooked++
            }

        qtm.declaredMethods.filter { it.name == "createWallpaperOpenAnimations" && it.parameterTypes.size == 6 }
            .forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (inRunner.get() != true) return
                        if (p.args[5] == true) return
                        runCatching {
                            val v = findView.invoke(p.thisObject, p.args[0])
                            if (v != null) {
                                p.args[5] = true
                                XposedBridge.log("$tag: closing into icon")
                            }
                        }
                    }
                })
                hooked++
            }
        XposedBridge.log("$tag: icon close hooks=$hooked")
    }
}
