package com.simrelay.m0.diagnostics

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.simrelay.m0.util.JsonText

enum class RoleAvailability {
    Available,
    Unavailable,
    UnsupportedByOs,
    QueryFailed
}

enum class ProvisioningRoute {
    PermissionGranted,
    RoleHeldPermissionMissing,
    RoleAvailableSystemAppRequired,
    RoleAvailableHolderConfigurationRequired,
    PrivilegedOrPlatformSignatureRequired,
    Unknown
}

private data class RoleState(
    val availability: RoleAvailability,
    val held: Boolean,
    val queryFailure: String? = null
)

data class ProvisioningPathSnapshot(
    val apiLevel: Int,
    val callAudioInterceptionGranted: Boolean,
    val roleName: String,
    val roleAvailability: RoleAvailability,
    val roleHeld: Boolean,
    val roleQueryFailure: String?,
    val systemApplication: Boolean,
    val route: ProvisioningRoute
) {
    val requiresSystemIntegration: Boolean
        get() = route in SystemIntegrationRoutes

    val summary: String
        get() = when (route) {
            ProvisioningRoute.PermissionGranted ->
                "CALL_AUDIO_INTERCEPTION is granted"
            ProvisioningRoute.RoleHeldPermissionMissing ->
                "System call streaming role is held but CALL_AUDIO_INTERCEPTION is still missing"
            ProvisioningRoute.RoleAvailableSystemAppRequired ->
                "System integration required: system-app installation, then role holder configuration"
            ProvisioningRoute.RoleAvailableHolderConfigurationRequired ->
                "System integration required: role holder configuration"
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired ->
                "System integration required: privileged or platform-signed installation"
            ProvisioningRoute.Unknown ->
                "Provisioning route could not be determined"
        }

    fun toJson(): String = JsonText.obj(
        "apiLevel" to apiLevel.toString(),
        "callAudioInterceptionGranted" to callAudioInterceptionGranted.toString(),
        "roleName" to JsonText.string(roleName),
        "roleAvailability" to JsonText.string(roleAvailability.name),
        "roleHeld" to roleHeld.toString(),
        "roleQueryFailure" to JsonText.string(roleQueryFailure),
        "systemApplication" to systemApplication.toString(),
        "route" to JsonText.string(route.name),
        "requiresSystemIntegration" to requiresSystemIntegration.toString(),
        "summary" to JsonText.string(summary)
    )

    companion object {
        const val SystemCallStreamingRole = "android.app.role.SYSTEM_CALL_STREAMING"

        private val SystemIntegrationRoutes = setOf(
            ProvisioningRoute.RoleAvailableSystemAppRequired,
            ProvisioningRoute.RoleAvailableHolderConfigurationRequired,
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired
        )

        fun capture(context: Context): ProvisioningPathSnapshot {
            val granted = context.checkSelfPermission(PermissionSnapshot.CallAudioInterception) ==
                PackageManager.PERMISSION_GRANTED
            val roleState = roleState(context)
            val systemApplication = context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            return ProvisioningPathSnapshot(
                apiLevel = Build.VERSION.SDK_INT,
                callAudioInterceptionGranted = granted,
                roleName = SystemCallStreamingRole,
                roleAvailability = roleState.availability,
                roleHeld = roleState.held,
                roleQueryFailure = roleState.queryFailure,
                systemApplication = systemApplication,
                route = ProvisioningPathEvaluator.evaluate(
                    callAudioInterceptionGranted = granted,
                    roleAvailability = roleState.availability,
                    roleHeld = roleState.held,
                    systemApplication = systemApplication
                )
            )
        }

        private fun roleState(context: Context): RoleState =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                RoleState(RoleAvailability.UnsupportedByOs, false)
            } else {
                queryRoleState(context)
            }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun queryRoleState(context: Context): RoleState = try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            when {
                roleManager == null -> RoleState(
                    RoleAvailability.QueryFailed,
                    false,
                    "RoleManager service is unavailable"
                )
                !roleManager.isRoleAvailable(SystemCallStreamingRole) ->
                    RoleState(RoleAvailability.Unavailable, false)
                else -> RoleState(
                    RoleAvailability.Available,
                    roleManager.isRoleHeld(SystemCallStreamingRole)
                )
            }
        } catch (throwable: Throwable) {
            RoleState(RoleAvailability.QueryFailed, false, throwable.javaClass.name)
        }
    }
}

object ProvisioningPathEvaluator {
    fun evaluate(
        callAudioInterceptionGranted: Boolean,
        roleAvailability: RoleAvailability,
        roleHeld: Boolean,
        systemApplication: Boolean
    ): ProvisioningRoute = when {
        callAudioInterceptionGranted -> ProvisioningRoute.PermissionGranted
        roleHeld -> ProvisioningRoute.RoleHeldPermissionMissing
        roleAvailability == RoleAvailability.Available && systemApplication ->
            ProvisioningRoute.RoleAvailableHolderConfigurationRequired
        roleAvailability == RoleAvailability.Available ->
            ProvisioningRoute.RoleAvailableSystemAppRequired
        roleAvailability == RoleAvailability.Unavailable ||
            roleAvailability == RoleAvailability.UnsupportedByOs ->
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired
        else -> ProvisioningRoute.Unknown
    }
}
