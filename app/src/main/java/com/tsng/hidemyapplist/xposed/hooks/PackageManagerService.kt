package com.tsng.hidemyapplist.xposed.hooks

import android.content.Context
import android.content.Intent
import android.content.pm.ParceledListSlice
import android.os.Binder
import android.os.Process
import com.tsng.hidemyapplist.BuildConfig
import com.tsng.hidemyapplist.JSONPreference
import com.tsng.hidemyapplist.xposed.XposedUtils.Companion.APPNAME
import com.tsng.hidemyapplist.xposed.XposedUtils.Companion.getRecursiveField
import com.tsng.hidemyapplist.xposed.XposedUtils.Companion.ld
import com.tsng.hidemyapplist.xposed.XposedUtils.Companion.li
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import kotlin.concurrent.thread

class PackageManagerService : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpp: LoadPackageParam) {
        if (lpp.packageName != "android") return
        val PKMS = XposedHelpers.findClass("com.android.server.pm.PackageManagerService", lpp.classLoader)
        XposedBridge.hookAllConstructors(PKMS, object : XC_MethodHook() {
            /* 建立Preference缓存 */
            var initialized = false
            var data = JSONPreference()
            lateinit var context: Context

            fun receiveJson(str: String) {
                initialized = true
                data = JSONPreference.fromJson(str)
            }

            fun isUseHook(callerName: String?, hookMethod: String): Boolean {
                if (callerName == APPNAME && !data.HookSelf) return false
                val tplName = data.Scope[callerName] ?: return false
                val template = data.Templates[tplName] ?: return false
                return template.EnableAllHooks or template.ApplyHooks.contains(hookMethod)
            }

            fun isToHide(callerName: String?, pkgstr: String?): Boolean {
                if (callerName == null || pkgstr == null) return false
                if (pkgstr.contains(callerName)) return false
                val tplName = data.Scope[callerName] ?: return false
                val template = data.Templates[tplName] ?: return false
                if (template.ExcludeWebview && pkgstr.contains(Regex("[Ww]ebview"))) return false
                if (template.HideAllApps) return true
                for (pkg in template.HideApps)
                    if (pkgstr.contains(pkg)) return true
                return false
            }

            fun isHideFile(callerName: String?, path: String?): Boolean {
                if (callerName == null || path == null) return false
                if (path.contains(callerName)) return false
                val tplName = data.Scope[callerName] ?: return false
                val template = data.Templates[tplName] ?: return false
                if (template.ExcludeWebview && path.contains(Regex("[Ww]ebview"))) return false
                if (template.HideTWRP && path.contains(Regex("/storage/emulated/(.*)/TWRP"))) return true
                if (template.HideAllApps && path.contains(Regex("/storage/emulated/(.*)/Android/data/"))) return true
                for (pkg in template.HideApps)
                    if (path.contains(pkg)) return true
                return false
            }

            /* Hook操作 */
            fun removeList(method: Method, hookName: String, pkgNameObjList: List<String>) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val callerUid = Binder.getCallingUid()
                        if (callerUid < Process.FIRST_APPLICATION_UID) return
                        val callerName = XposedHelpers.callMethod(param.thisObject, "getNameForUid", callerUid) as String
                        if (!isUseHook(callerName, hookName)) return
                        var isHidden = false
                        val iterator = (param.result as ParceledListSlice<*>).list.iterator()
                        val removed = mutableListOf<String>()
                        while (iterator.hasNext()) {
                            val str = getRecursiveField(iterator.next(), pkgNameObjList) as String?
                            if (isToHide(callerName, str)) {
                                iterator.remove()
                                isHidden = true
                                if (data.DetailLog) removed.add(str!!)
                            }
                        }
                        if (isHidden) li("@Hide PKMS caller: $callerName method: ${param.method.name}")
                        if (isHidden && data.DetailLog) ld("removeList $removed")
                    }
                })
            }

            fun setResult(method: Method, hookName: String, result: Any?) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callerUid = Binder.getCallingUid()
                        if (callerUid < Process.FIRST_APPLICATION_UID) return
                        val callerName = XposedHelpers.callMethod(param.thisObject, "getNameForUid", callerUid) as String
                        if (!isUseHook(callerName, hookName)) return
                        if (isToHide(callerName, param.args[0] as String)) {
                            param.result = result
                            li("@Hide PKMS caller: $callerName method: ${param.method.name} param: ${param.args[0]}")
                        }
                    }
                })
            }

            /* hook getPackageUid作为通信服务 */
            inner class HMAService : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callerUid = Binder.getCallingUid()
                    if (callerUid < Process.FIRST_APPLICATION_UID) return
                    val callerName = XposedHelpers.callMethod(param.thisObject, "getNameForUid", callerUid) as String
                    /* 服务模式，执行自定义行为 */
                    val arg = param.args[0] as String
                    when {
                        arg == "checkHMAServiceVersion" -> {
                            param.result = BuildConfig.VERSION_CODE
                            return
                        }
                        arg.contains("providePreference") -> {
                            receiveJson(arg.split("#")[1])
                            param.result = 1
                            return
                        }
                        arg.contains("callIsUseHook") -> {
                            val split = arg.split("#")
                            if (split.size != 3) param.result = 2
                            else param.result = if (isUseHook(split[1], split[2])) 1 else 2
                            return
                        }
                        arg.contains("callIsToHide") -> {
                            val split = arg.split("#")
                            if (split.size != 3) param.result = 2
                            else param.result = if (isToHide(split[1], split[2])) 1 else 2
                            return
                        }
                        arg.contains("callIsHideFile") -> {
                            val split = arg.split("#")
                            if (split.size != 3) param.result = 2
                            else param.result = if (isHideFile(split[1], split[2])) 1 else 2
                            return
                        }
                    }
                    /* 非服务模式，正常hook */
                    if (!isUseHook(callerName, "ID detections")) return
                    if (isToHide(callerName, param.args[0] as String)) {
                        param.result = -1
                        li("@Hide PKMS caller: $callerName method: ${param.method.name} param: ${param.args[0]}")
                    }
                }
            }

            /* 载入PackageManagerService */
            override fun afterHookedMethod(param: MethodHookParam) {
                context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                li("System hook installed")
                thread {
                    li("Waiting for preference provider")
                    while (!initialized) {
                        try {
                            context.startService(Intent().apply {
                                setClassName(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + ".ProvidePreferenceService")
                            })
                        } catch (e: Exception) {
                        }
                        Thread.sleep(1000)
                    }
                    li("Preferences initialized")
                }
                for (method in PKMS.declaredMethods) when (method.name) {
                    "getPackageUid" -> XposedBridge.hookMethod(method, HMAService())

                    "getInstalledPackages",
                    "getInstalledApplications",
                    "getPackagesHoldingPermissions",
                    "queryInstrumentation" -> removeList(method, "API requests", listOf("packageName"))

                    "getPackageInfo",
                    "getPackageGids",
                    "getApplicationInfo",
                    "getInstallerPackageName" -> setResult(method, "API requests", null)

                    "queryIntentActivities",
                    "queryIntentActivityOptions",
                    "queryIntentReceivers",
                    "queryIntentServices",
                    "queryIntentContentProviders" -> removeList(method, "Intent queries", listOf("activityInfo", "packageName"))

                    "getPackagesForUid" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val callerUid = Binder.getCallingUid()
                            if (callerUid < Process.FIRST_APPLICATION_UID) return
                            val callerName = XposedHelpers.callMethod(param.thisObject, "getNameForUid", callerUid) as String
                            if (!isUseHook(callerName, "ID detections")) return
                            if (param.result != null) {
                                var change = false
                                val list = mutableListOf<String>()
                                val removed = mutableListOf<String>()
                                for (str in param.result as Array<String>)
                                    if (isToHide(callerName, str)) {
                                        change = true
                                        if (data.DetailLog) removed.add(str)
                                    } else list.add(str)
                                if (change) {
                                    param.result = list.toTypedArray()
                                    li("@Hide PKMS caller: $callerName method: ${param.method.name}")
                                    if (data.DetailLog) ld("removeList $removed")
                                }
                            }
                        }
                    })
                }
            }
        })
    }
}