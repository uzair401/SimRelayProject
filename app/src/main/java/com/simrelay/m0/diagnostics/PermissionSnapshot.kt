package com.simrelay.m0.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.simrelay.m0.util.JsonText

enum class PermissionDeclarationState {
    DeclaredAndGranted,
    DeclaredNotGranted,
    NotDeclared
}

enum class PermissionProtection {
    Normal,
    Runtime,
    SignatureOrPrivilegedOrRole,
    Unknown
}

data class PermissionGrant(
    val name: String,
    val declarationState: PermissionDeclarationState,
    val protection: PermissionProtection
) {
    val declared: Boolean
        get() = declarationState != PermissionDeclarationState.NotDeclared

    val granted: Boolean
        get() = declarationState == PermissionDeclarationState.DeclaredAndGranted

    val unavailableToNormalApp: Boolean
        get() = declared && !granted && protection == PermissionProtection.SignatureOrPrivilegedOrRole

    fun toJson(): String = JsonText.obj(
        "name" to JsonText.string(name),
        "declared" to declared.toString(),
        "granted" to granted.toString(),
        "declarationState" to JsonText.string(declarationState.name),
        "protection" to JsonText.string(protection.name),
        "unavailableToNormalApp" to unavailableToNormalApp.toString()
    )
}

data class PermissionSnapshot(val grants: List<PermissionGrant>) {
    fun isGranted(name: String): Boolean = grants.firstOrNull { it.name == name }?.granted == true

    fun toJson(): String = JsonText.obj(
        "permissions" to JsonText.array(grants.map(PermissionGrant::toJson))
    )

    companion object {
        const val CallAudioInterception = "android.permission.CALL_AUDIO_INTERCEPTION"
        const val CaptureAudioOutput = "android.permission.CAPTURE_AUDIO_OUTPUT"
        const val ModifyPhoneState = "android.permission.MODIFY_PHONE_STATE"

        val monitoredPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            CallAudioInterception,
            CaptureAudioOutput,
            ModifyPhoneState
        )

        fun capture(context: Context): PermissionSnapshot {
            val packageInfo = packageInfo(context)
            val requested = packageInfo.requestedPermissions.orEmpty().toSet()
            val permissions = (requested + monitoredPermissions).sorted()
            return PermissionSnapshot(
                permissions.map { permission ->
                    PermissionGrant(
                        permission,
                        PermissionStateMapper.declarationState(
                            declared = permission in requested,
                            granted = context.packageManager.checkPermission(permission, context.packageName) ==
                                PackageManager.PERMISSION_GRANTED
                        ),
                        permissionProtection(context.packageManager, permission)
                    )
                }
            )
        }

        @Suppress("DEPRECATION")
        private fun packageInfo(context: Context) =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }

        @Suppress("DEPRECATION")
        private fun permissionProtection(
            packageManager: PackageManager,
            permission: String
        ): PermissionProtection = try {
            val info = packageManager.getPermissionInfo(permission, 0)
            PermissionProtectionMapper.fromProtectionLevel(info.protectionLevel)
        } catch (_: PackageManager.NameNotFoundException) {
            PermissionProtection.Unknown
        } catch (_: SecurityException) {
            PermissionProtection.Unknown
        }
    }
}

object PermissionStateMapper {
    fun declarationState(declared: Boolean, granted: Boolean): PermissionDeclarationState = when {
        !declared -> PermissionDeclarationState.NotDeclared
        granted -> PermissionDeclarationState.DeclaredAndGranted
        else -> PermissionDeclarationState.DeclaredNotGranted
    }
}

object PermissionProtectionMapper {
    fun fromProtectionLevel(protectionLevel: Int): PermissionProtection {
        val base = protectionLevel and ProtectionBaseMask
        val privileged = protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0
        return when {
            base == PermissionInfo.PROTECTION_DANGEROUS -> PermissionProtection.Runtime
            base == PermissionInfo.PROTECTION_SIGNATURE || privileged ->
                PermissionProtection.SignatureOrPrivilegedOrRole
            base == PermissionInfo.PROTECTION_NORMAL -> PermissionProtection.Normal
            else -> PermissionProtection.Unknown
        }
    }

    private const val ProtectionBaseMask = 0xF
}
