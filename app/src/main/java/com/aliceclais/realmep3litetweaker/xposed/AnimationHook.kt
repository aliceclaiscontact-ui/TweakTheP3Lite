package com.aliceclais.realmep3litetweaker.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method

/** ДИАГНОСТИКА v2: ничего не меняет, только пишет в лог LSPosed (тег P3DIAG2). */
class AnimationHook : IXposedHookLoadPackage {

    private val tag = "P3DIAG2"

    private fun sig(m: Method) =
        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"

    private fun short(o: Any?): String = try { o.toString().take(160) } catch (_: Throwable) { "?" }

    private fun logCalls(c: Class<*>, filter: (Method) -> Boolean) {
        for (m in c.declaredMethods) {
            if (!filter(m)) continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("$tag: >> ${c.simpleName}.${sig(m)} args=${p.args?.joinToString { short(it) }}")
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("$tag: << ${c.simpleName}.${m.name} = ${short(p.result)}")
                    }
                })
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher3") return
        if (File("/data/local/tmp/disable_p3_anim").exists()) return
        val cl = lpparam.classLoader
        XposedBridge.log("$tag: loaded in ${lpparam.processName}")

        // Полный дамп Oplus-классов анимации (методы + поля)
        val dump = listOf(
            "com.android.launcher3.pullup.PullUpTransitionManager",
            "com.android.launcher3.pullup.PullUpLauncherManager",
            "com.android.launcher3.OplusLauncherAppTransitionValueAnimator",
            "com.android.launcher3.QuickstepTransitionManager\$AppLaunchAnimationRunner"
        )
        for (n in dump) {
            val c = XposedHelpers.findClassIfExists(n, cl)
            if (c == null) { XposedBridge.log("$tag: NOCLASS $n"); continue }
            XposedBridge.log("$tag: DUMP $n super=${c.superclass?.name}")
            c.declaredFields.forEach { XposedBridge.log("$tag:   F ${it.type.simpleName} ${it.name}") }
            c.declaredMethods.forEach { XposedBridge.log("$tag:   M ${sig(it)}") }
        }

        // Логируем вызовы (без изменений): ключевые методы запуска
        XposedHelpers.findClassIfExists("com.android.launcher3.QuickstepTransitionManager", cl)?.let { c ->
            logCalls(c) {
                it.name in setOf(
                    "getActivityLaunchOptions", "createAppLaunchRunner", "isHomeRoleHeld",
                    "checkReturnAnimationsFlags", "composeIconLaunchAnimator",
                    "getOpeningWindowAnimators", "shouldPlayFallbackClosingAnimation",
                    "getFallbackClosingWindowAnimators", "getClosingWindowAnimators"
                )
            }
        }
        XposedHelpers.findClassIfExists("com.android.launcher3.pullup.PullUpTransitionManager", cl)?.let { c ->
            logCalls(c) { !it.name.startsWith("lambda$") && !it.name.startsWith("access$") }
        }
        XposedHelpers.findClassIfExists("com.android.launcher3.QuickstepTransitionManager\$AppLaunchAnimationRunner", cl)?.let { c ->
            logCalls(c) { true }
        }

        // Unisoc "union anim": решает, регистрировать ли legacy remote animations (только лог)
        runCatching {
            val u = XposedHelpers.findClass("android.app.unipnp.UnisocUnionManager", cl)
            u.declaredMethods.filter { it.name == "useUnionAnim" || it.name == "getUniEnterAnimDuration" }.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("$tag: << UnisocUnionManager.${m.name} args=${p.args?.joinToString { short(it) }} = ${short(p.result)}")
                    }
                })
            }
        }.onFailure { XposedBridge.log("$tag: unisoc hook fail $it") }

        // makeRemoteAnimation / makeRemoteTransition с аргументами
        runCatching {
            val ao = XposedHelpers.findClass("android.app.ActivityOptions", cl)
            ao.declaredMethods.filter { it.name.startsWith("makeRemote") }.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("$tag: >> ActivityOptions.${sig(m)} args=${p.args?.joinToString { short(it) }}")
                    }
                })
            }
        }
    }
}
