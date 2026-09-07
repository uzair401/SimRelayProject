package com.simrelay.m0.diagnostics

import android.content.pm.PermissionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test
    fun mapsDeclarationAndGrantIndependently() {
        assertEquals(
            PermissionDeclarationState.NotDeclared,
            PermissionStateMapper.declarationState(declared = false, granted = true)
        )
        assertEquals(
            PermissionDeclarationState.DeclaredNotGranted,
            PermissionStateMapper.declarationState(declared = true, granted = false)
        )
        assertEquals(
            PermissionDeclarationState.DeclaredAndGranted,
            PermissionStateMapper.declarationState(declared = true, granted = true)
        )
    }

    @Test
    fun classifiesRuntimeAndPrivilegedPermissionProtection() {
        assertEquals(
            PermissionProtection.Runtime,
            PermissionProtectionMapper.fromProtectionLevel(PermissionInfo.PROTECTION_DANGEROUS)
        )
        assertEquals(
            PermissionProtection.SignatureOrPrivilegedOrRole,
            PermissionProtectionMapper.fromProtectionLevel(
                PermissionInfo.PROTECTION_SIGNATURE or PermissionInfo.PROTECTION_FLAG_PRIVILEGED
            )
        )
    }

    @Test
    fun marksOnlyDeniedDeclaredPrivilegedPermissionUnavailableToNormalApp() {
        val denied = PermissionGrant(
            PermissionSnapshot.CallAudioInterception,
            PermissionDeclarationState.DeclaredNotGranted,
            PermissionProtection.SignatureOrPrivilegedOrRole
        )
        val granted = denied.copy(declarationState = PermissionDeclarationState.DeclaredAndGranted)
        val absent = denied.copy(declarationState = PermissionDeclarationState.NotDeclared)

        assertTrue(denied.unavailableToNormalApp)
        assertFalse(granted.unavailableToNormalApp)
        assertFalse(absent.unavailableToNormalApp)
    }
}
