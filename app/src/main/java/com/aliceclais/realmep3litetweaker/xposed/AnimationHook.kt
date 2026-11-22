package com.aliceclais.realmep3litetweaker.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * ДИАГНОСТИЧЕСКАЯ версия: ничего не меняет, только пишет в лог LSPosed.
 * Тег для grep: P3DIAG
 */
class AnimationHook : IXposedHookLoadPackage {

    private val tag = "P3DIAG"

    private val interesting = Regex(
        "checkUx|isLowRam|hasControlRemote|registerRemote|PullUp|Oplus|Unisoc|RemoteAnim|RemoteTransition",
        RegexOption.IGNORE_CASE
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher3") return
        if (File("/data/local/tmp/disable_p3_anim").exists()) return

        val cl = lpparam.classLoader
        XposedBridge.log("$tag: loaded in ${lpparam.processName}")

        // 1) Какие классы с интересными именами есть в dex
        runCatching {
            val pathList = XposedHelpers.getObjectField(cl, "pathList")
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as Array<*>
            for (el in elements) {
                val dex = XposedHelpers.getObjectField(el, "dexFile") ?: continue
                val entries = XposedHelpers.callMethod(dex, "entries") as java.util.Enumeration<*>
                while (entries.hasMoreElements()) {
                    val n = entries.nextElement() as String
                    if (n.contains("TransitionManager") || n.contains("FloatingIconView") ||
                        n.contains("PullUp") || n.contains("RemoteAnim") ||
                        n.contains("RemoteTransition") || n.contains("Oplus", true) ||
                        n.contains("Unisoc", true)
                    ) {
                        XposedBridge.log("$tag: CLASS $n")
                    }
                }
            }
        }.onFailure { XposedBridge.log("$tag: class scan failed: $it") }

        // 2) Дамп методов ключевых классов + логирующие хуки (без изменения результата)
        val targets = listOf(
            "com.android.launcher3.QuickstepTransitionManager",
            "com.android.launcher3.PullUpTransitionManager",
            "com.android.quickstep.PullUpTransitionManager",
            "com.android.launcher3.views.FloatingIconView"
        )
        for (name in targets) {
            val c = XposedHelpers.findClassIfExists(name, cl)
            if (c == null) {
                XposedBridge.log("$tag: NOCLASS $name")
                continue
            }
            XposedBridge.log("$tag: DUMP $name")
            for (m in c.declaredMethods) {
                val sig = "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                XposedBridge.log("$tag:   $sig")
                if (interesting.containsMatchIn(m.name)) {
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                XposedBridge.log("$tag: CALL ${c.simpleName}.$sig -> ${param.result}")
                            }
                        })
                    }
                }
            }
        }

        // 3) Каждый вызов ActivityOptions.makeRemoteAnimation / makeRemoteTransition
        runCatching {
            val ao = XposedHelpers.findClass("android.app.ActivityOptions", cl)
            ao.declaredMethods.filter { it.name.startsWith("makeRemote") }.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$tag: CALL ActivityOptions.${m.name}")
                    }
                })
            }
        }
    }
}
