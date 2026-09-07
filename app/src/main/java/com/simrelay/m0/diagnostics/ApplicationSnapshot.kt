package com.simrelay.m0.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.simrelay.m0.util.JsonText

data class ApplicationSnapshot(
    val packageName: String,
    val installPath: String,
    val uid: Int,
    val versionName: String?,
    val versionCode: Long,
    val requestedPermissions: List<String>
) {
    fun toJson(): String = JsonText.obj(
        "packageName" to JsonText.string(packageName),
        "installPath" to JsonText.string(installPath),
        "uid" to uid.toString(),
        "versionName" to JsonText.string(versionName),
        "versionCode" to versionCode.toString(),
        "requestedPermissions" to JsonText.array(requestedPermissions.map(JsonText::string))
    )

    companion object {
        fun capture(context: Context, permissions: PermissionSnapshot): ApplicationSnapshot {
            val packageInfo = packageInfo(context)
            return ApplicationSnapshot(
                packageName = context.packageName,
                installPath = context.applicationInfo.sourceDir,
                uid = context.applicationInfo.uid,
                versionName = packageInfo.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                requestedPermissions = permissions.grants.filter(PermissionGrant::declared).map(PermissionGrant::name)
            )
        }

        @Suppress("DEPRECATION")
        private fun packageInfo(context: Context) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
    }
}
