package com.simrelay.m0.diagnostics

import android.os.Build
import com.simrelay.m0.util.JsonText

data class DeviceSnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val fingerprint: String,
    val buildId: String,
    val buildType: String,
    val buildTags: String,
    val bootloader: String,
    val socManufacturer: String?,
    val socModel: String?,
    val sdkInt: Int,
    val release: String,
    val securityPatch: String,
    val supportedAbis: List<String>
) {
    fun toJson(): String = JsonText.obj(
        "manufacturer" to JsonText.string(manufacturer),
        "brand" to JsonText.string(brand),
        "model" to JsonText.string(model),
        "device" to JsonText.string(device),
        "product" to JsonText.string(product),
        "hardware" to JsonText.string(hardware),
        "board" to JsonText.string(board),
        "fingerprint" to JsonText.string(fingerprint),
        "buildId" to JsonText.string(buildId),
        "buildType" to JsonText.string(buildType),
        "buildTags" to JsonText.string(buildTags),
        "bootloader" to JsonText.string(bootloader),
        "socManufacturer" to JsonText.string(socManufacturer),
        "socModel" to JsonText.string(socModel),
        "sdkInt" to sdkInt.toString(),
        "release" to JsonText.string(release),
        "securityPatch" to JsonText.string(securityPatch),
        "supportedAbis" to JsonText.array(supportedAbis.map(JsonText::string))
    )

    companion object {
        fun capture() = DeviceSnapshot(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            fingerprint = Build.FINGERPRINT,
            buildId = Build.ID,
            buildType = Build.TYPE,
            buildTags = Build.TAGS,
            bootloader = Build.BOOTLOADER,
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                null
            },
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
    }
}
