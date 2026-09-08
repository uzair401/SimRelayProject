package com.simrelay.m0.diagnostics

import com.simrelay.m0.provisioning.CallStreamingQualificationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningPathTest {
    @Test
    fun treatsPreRoleManagerPlatformsAsPrivilegedOrPlatformSignatureOnly() {
        assertEquals(
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.UnsupportedByOs,
                roleHeld = false,
                systemApplication = false
            )
        )
    }

    @Test
    fun treatsBuildWithoutRoleBranchAsPrivilegedOrPlatformSignatureOnly() {
        assertEquals(
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.Unavailable,
                roleHeld = false,
                systemApplication = false
            )
        )
    }

    @Test
    fun distinguishesSystemAppInstallationFromRoleHolderConfiguration() {
        assertEquals(
            ProvisioningRoute.RoleAvailableSystemAppRequired,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.Available,
                roleHeld = false,
                systemApplication = false
            )
        )
        assertEquals(
            ProvisioningRoute.RoleAvailableHolderConfigurationRequired,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.Available,
                roleHeld = false,
                systemApplication = true
            )
        )
    }

    @Test
    fun reportsRoleHeldWithoutPermissionAsItsOwnState() {
        assertEquals(
            ProvisioningRoute.RoleHeldPermissionMissing,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.Available,
                roleHeld = true,
                systemApplication = true
            )
        )
    }

    @Test
    fun grantedPermissionOutranksEveryProvisioningRoute() {
        val routes = RoleAvailability.entries.map { availability ->
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = true,
                roleAvailability = availability,
                roleHeld = false,
                systemApplication = false
            )
        }

        assertTrue(routes.all { it == ProvisioningRoute.PermissionGranted })
    }

    @Test
    fun reportsUnknownWhenRoleQueryFailed() {
        assertEquals(
            ProvisioningRoute.Unknown,
            ProvisioningPathEvaluator.evaluate(
                callAudioInterceptionGranted = false,
                roleAvailability = RoleAvailability.QueryFailed,
                roleHeld = false,
                systemApplication = false
            )
        )
    }

    @Test
    fun marksOnlyUnobtainableRoutesAsRequiringSystemIntegration() {
        assertTrue(snapshot(ProvisioningRoute.RoleAvailableSystemAppRequired).requiresSystemIntegration)
        assertTrue(
            snapshot(ProvisioningRoute.RoleAvailableHolderConfigurationRequired).requiresSystemIntegration
        )
        assertTrue(
            snapshot(ProvisioningRoute.PrivilegedOrPlatformSignatureRequired).requiresSystemIntegration
        )
        assertFalse(snapshot(ProvisioningRoute.PermissionGranted).requiresSystemIntegration)
        assertFalse(snapshot(ProvisioningRoute.RoleHeldPermissionMissing).requiresSystemIntegration)
        assertFalse(snapshot(ProvisioningRoute.Unknown).requiresSystemIntegration)
    }

    @Test
    fun statesSystemIntegrationRequirementInsteadOfOfferingAGrant() {
        val summaries = listOf(
            ProvisioningRoute.RoleAvailableSystemAppRequired,
            ProvisioningRoute.RoleAvailableHolderConfigurationRequired,
            ProvisioningRoute.PrivilegedOrPlatformSignatureRequired
        ).map { snapshot(it).summary }

        assertTrue(summaries.all { it.startsWith("System integration required") })
        assertFalse(summaries.any { it.contains("Grant", ignoreCase = true) })
    }

    @Test
    fun serializesEveryProvisioningFieldForQualificationRecords() {
        val json = snapshot(ProvisioningRoute.RoleAvailableSystemAppRequired).copy(
            apiLevel = 34,
            roleAvailability = RoleAvailability.Available
        ).toJson()

        assertTrue(json.contains("\"apiLevel\": 34"))
        assertTrue(json.contains("\"roleName\": \"android.app.role.SYSTEM_CALL_STREAMING\""))
        assertTrue(json.contains("\"roleAvailability\": \"Available\""))
        assertTrue(json.contains("\"route\": \"RoleAvailableSystemAppRequired\""))
        assertTrue(json.contains("\"requiresSystemIntegration\": true"))
        assertTrue(json.contains("\"qualificationComponentDeclared\": true"))
        assertTrue(json.contains("\"summary\": \"System integration required"))
    }

    @Test
    fun matchesTheRoleQualificationContractDeclaredByAosp() {
        assertEquals(
            "android.telecom.CallStreamingService",
            CallStreamingQualificationService.ServiceAction
        )
        assertEquals(
            "android.permission.BIND_CALL_STREAMING_SERVICE",
            CallStreamingQualificationService.BindPermission
        )
        assertEquals(
            "android.app.role.SYSTEM_CALL_STREAMING",
            ProvisioningPathSnapshot.SystemCallStreamingRole
        )
    }

    @Test
    fun reportsMissingQualificationComponentWithoutChangingTheProvisioningRoute() {
        val declared = snapshot(ProvisioningRoute.RoleAvailableSystemAppRequired)
        val undeclared = declared.copy(qualificationComponentDeclared = false)

        assertEquals(declared.route, undeclared.route)
        assertTrue(undeclared.toJson().contains("\"qualificationComponentDeclared\": false"))
    }

    @Test
    fun preservesRoleQueryFailureClassWithoutClaimingAvailability() {
        val snapshot = snapshot(ProvisioningRoute.Unknown).copy(
            roleAvailability = RoleAvailability.QueryFailed,
            roleQueryFailure = "java.lang.SecurityException"
        )

        assertTrue(snapshot.toJson().contains("\"roleQueryFailure\": \"java.lang.SecurityException\""))
        assertFalse(snapshot.requiresSystemIntegration)
    }

    private fun snapshot(route: ProvisioningRoute) = ProvisioningPathSnapshot(
        apiLevel = 33,
        callAudioInterceptionGranted = route == ProvisioningRoute.PermissionGranted,
        roleName = ProvisioningPathSnapshot.SystemCallStreamingRole,
        roleAvailability = RoleAvailability.Unavailable,
        roleHeld = route == ProvisioningRoute.RoleHeldPermissionMissing,
        roleQueryFailure = null,
        systemApplication = false,
        qualificationComponentDeclared = true,
        route = route
    )
}
