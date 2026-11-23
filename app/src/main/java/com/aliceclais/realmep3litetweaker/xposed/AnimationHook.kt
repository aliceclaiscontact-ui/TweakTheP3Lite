package com.aliceclais.realmep3litetweaker.xposed

import android.app.ActivityManager
import android.app.ActivityOptions
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

class AnimationHook : IXposedHookLoadPackage {

    private val tag = "TweakTheP3Lite"
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
        val cl = lpparam.classLoader
        runCatching { installLaunchFix(cl) }.onFailure { XposedBridge.log("$tag: launch fix failed: $it") }
        runCatching { installSquareCorners(cl) }.onFailure { XposedBridge.log("$tag: corners failed: $it") }
    }

    private fun extractPackageName(intent: Intent?, item: Any?): String? {
        intent?.component?.packageName?.let { return it }
        intent?.`package`?.let { return it }
        if (item != null) {
            runCatching {
                val targetComp = XposedHelpers.callMethod(item, "getTargetComponent") as? ComponentName
                if (targetComp != null) return targetComp.packageName
            }
            runCatching {
                val itemIntent = XposedHelpers.callMethod(item, "getIntent") as? Intent
                itemIntent?.component?.packageName?.let { return it }
                itemIntent?.`package`?.let { return it }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun isAppWarmInMemory(context: Context, pkg: String): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false

            val running = runCatching { am.getRunningTasks(30) }.getOrNull().orEmpty()
            val inRunning = running.any { info ->
                val numActivities = runCatching { XposedHelpers.getIntField(info, "numActivities") }.getOrDefault(1)
                (info.baseActivity?.packageName == pkg || info.topActivity?.packageName == pkg) && numActivities > 0
            }
            if (inRunning) return true

            val recent = runCatching {
                am.getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            }.getOrNull().orEmpty()
            recent.any { info ->
                info.id != -1 && (
                    info.baseActivity?.packageName == pkg ||
                    info.topActivity?.packageName == pkg ||
                    info.baseIntent?.component?.packageName == pkg
                )
            }
        }.getOrDefault(false)
    }

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
                        val view = p.args[0] as? View
                        val intent = p.args[1] as? Intent
                        val item = p.args[2]
                        val ctx = p.thisObject as? Context
                        val pkg = extractPackageName(intent, item)
                        val isWarm = if (ctx != null && pkg != null) isAppWarmInMemory(ctx, pkg) else false
                        pending.set(arrayOf(view, item, pkg, isWarm))
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
                    val isWarm = ctx[3] as? Boolean ?: false
                    busy.set(true)
                    try {
                        if (isWarm) {
                            val wrapper = p.result ?: return
                            val scaleOpts = ActivityOptions.makeScaleUpAnimation(
                                view, 0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1)
                            )
                            XposedHelpers.setObjectField(wrapper, "options", scaleOpts)
                            XposedBridge.log("$tag: warm scaleUp applied for ${ctx[2]}")
                        } else {
                            val r = getOpts.invoke(p.thisObject, view, ctx[1])
                            if (r != null) {
                                p.result = r
                                XposedBridge.log("$tag: cold icon morph applied for ${ctx[2]}")
                            }
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
        XposedBridge.log("$tag: launch fix installed, hooks=$hooked")
    }

    private fun installSquareCorners(cl: ClassLoader) {
        var hooked = 0

        XposedHelpers.findClassIfExists("com.android.systemui.shared.system.QuickStepContract", cl)
            ?.declaredMethods?.filter { it.name == "getWindowCornerRadius" && it.returnType == Float::class.javaPrimitiveType }
            ?.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) { p.result = 0f }
                })
                hooked++
            }

        XposedHelpers.findClassIfExists("com.android.launcher3.views.FloatingIconView", cl)
            ?.declaredMethods?.filter { it.name == "update" }
            ?.forEach { m ->
                val idx = m.parameterTypes.indexOfLast { it == Float::class.javaPrimitiveType }
                if (idx < 0) return@forEach
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) { p.args[idx] = 0f }
                })
                hooked++
            }
        XposedBridge.log("$tag: square corners installed, hooks=$hooked")
    }
}
