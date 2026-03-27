package com.mobilenext.devicekit;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.IBinder;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Standalone entry point for listing installed packages via app_process.
 *
 * Usage:
 *   adb shell CLASSPATH=/path/to/classes.dex app_process / com.mobilenext.devicekit.PackageLister
 *
 * Outputs a JSON array of {packageName, appName, version} to stdout.
 */
public class PackageLister {

    public static void main(String[] args) {
        try {
            // Get the IPackageManager binder via ServiceManager (hidden API)
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "package");

            // Get IPackageManager.Stub.asInterface(binder)
            Class<?> ipmStub = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = ipmStub.getMethod("asInterface", IBinder.class);
            Object ipm = asInterface.invoke(null, binder);

            // Call getInstalledPackages(flags, userId)
            Method getInstalledPackages = ipm.getClass().getMethod("getInstalledPackages", long.class, int.class);
            Object parceledList = getInstalledPackages.invoke(ipm, 0L, 0);

            // ParceledListSlice.getList()
            Method getList = parceledList.getClass().getMethod("getList");
            @SuppressWarnings("unchecked")
            List<PackageInfo> packages = (List<PackageInfo>) getList.invoke(parceledList);

            JSONArray result = new JSONArray();

            for (PackageInfo pkg : packages) {
                String packageName = pkg.packageName;
                String versionName = pkg.versionName != null ? pkg.versionName : "";
                String displayName = packageName;

                ApplicationInfo appInfo = pkg.applicationInfo;
                if (appInfo != null) {
                    if (appInfo.nonLocalizedLabel != null) {
                        displayName = appInfo.nonLocalizedLabel.toString();
                    } else if (appInfo.labelRes != 0 && appInfo.sourceDir != null) {
                        try {
                            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
                            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
                            addAssetPath.invoke(assets, appInfo.sourceDir);
                            Resources res = new Resources(assets, new DisplayMetrics(), new Configuration());
                            String label = res.getString(appInfo.labelRes);
                            if (label != null && !label.isEmpty()) {
                                displayName = label;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                JSONObject entry = new JSONObject();
                entry.put("packageName", packageName);
                entry.put("appName", displayName);
                entry.put("version", versionName);
                result.put(entry);
            }

            System.out.println(result.toString());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
