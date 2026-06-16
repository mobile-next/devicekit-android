@file:JvmName("PackageLister")

package com.mobilenext.devicekit

import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.IBinder
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Standalone entry point for listing installed packages via app_process.
 *
 * Usage:
 *   adb shell CLASSPATH=/path/to/classes.dex app_process / com.mobilenext.devicekit.PackageLister
 *
 * Outputs a JSON array of {packageName, appName, version} to stdout.
 */
fun main(args: Array<String>) {
    try {
        // Get the IPackageManager binder via ServiceManager (hidden API)
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "package") as IBinder

        // Get IPackageManager.Stub.asInterface(binder)
        val ipmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterface = ipmStub.getMethod("asInterface", IBinder::class.java)
        val ipm = asInterface.invoke(null, binder)

        // Call getInstalledPackages(flags, userId)
        val getInstalledPackages = ipm.javaClass.getMethod(
            "getInstalledPackages",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val parceledList = getInstalledPackages.invoke(ipm, 0L, 0)

        // ParceledListSlice.getList()
        val getList = parceledList.javaClass.getMethod("getList")
        @Suppress("UNCHECKED_CAST")
        val packages = getList.invoke(parceledList) as List<PackageInfo>

        val result = JSONArray()

        for (pkg in packages) {
            val packageName = pkg.packageName
            val versionName = pkg.versionName ?: ""
            var displayName = packageName

            val appInfo = pkg.applicationInfo
            if (appInfo != null) {
                if (appInfo.nonLocalizedLabel != null) {
                    displayName = appInfo.nonLocalizedLabel.toString()
                } else if (appInfo.labelRes != 0 && appInfo.sourceDir != null) {
                    try {
                        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                        addAssetPath.invoke(assets, appInfo.sourceDir)
                        val res = Resources(assets, DisplayMetrics(), Configuration())
                        val label = res.getString(appInfo.labelRes)
                        if (label.isNotEmpty()) {
                            displayName = label
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }

            val entry = JSONObject()
            entry.put("packageName", packageName)
            entry.put("appName", displayName)
            entry.put("version", versionName)
            result.put(entry)
        }

        println(result.toString())
    } catch (e: Exception) {
        System.err.println("Error: " + e.message)
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}
