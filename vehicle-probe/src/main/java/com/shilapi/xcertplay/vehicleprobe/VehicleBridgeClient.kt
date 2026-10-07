// carlito | DiPlay client for the separately installed GD vehicle bridge APK.
package com.shilapi.xcertplay.vehicleprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.geely.desktop.vehicle.properties.IVehicleProperties
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public data contract only: no ECARX/OneOS/VHAL code or model address tables. */
class VehicleBridgeClient(context: Context) : Closeable {
    private val context = context.applicationContext
    private val ready = CountDownLatch(1)
    @Volatile private var remote: IVehicleProperties? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IVehicleProperties.Stub.asInterface(binder); ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; ready.countDown() }
        override fun onBindingDied(name: ComponentName?) { remote = null; ready.countDown() }
        override fun onNullBinding(name: ComponentName?) { ready.countDown() }
    }

    private fun service(): IVehicleProperties {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Vehicle bridge calls must run off the UI thread" }
        remote?.let { return it }
        if (!bound) {
            try { context.packageManager.getPackageInfo(BRIDGE_PACKAGE, 0) }
            catch (_: PackageManager.NameNotFoundException) { throw IllegalStateException("请先安装车辆数据桥") }
            bound = context.bindService(Intent().setComponent(ComponentName(BRIDGE_PACKAGE,
                "$BRIDGE_PACKAGE.VehiclePropertiesService")), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "车辆数据桥未响应，请安装支持属性接口的版本" }
        }
        check(ready.await(10, TimeUnit.SECONDS)) { "车辆数据桥连接超时，请重试" }
        return remote ?: throw IllegalStateException("车辆数据桥连接已断开，请重试")
    }
    fun status(): Bundle = service().status
    /** Normalized category values; missing keys are unavailable, never silently replaced with zero. */
    fun readProperties(): Bundle = service().readProperties("")
    /** carlito | A live consumer reads only its fields, without polling every configured property. */
    fun readProperties(fields: Set<String>): Bundle {
        val profile = activeProfile() ?: throw IllegalStateException("请先保存车型属性配置")
        val bindings = profile.bindings.filter { it.field.name in fields }
        if (bindings.isEmpty()) return Bundle().apply { putInt("schema", 1) }
        return service().readProperties(VehiclePropertyProfiles.encode(profile.copy(bindings = bindings)))
    }
    internal fun presets(): List<VehiclePropertyProfile> = service().let { api ->
        api.modelIds.map { VehiclePropertyProfiles.decode(api.getPreset(it)) }
    }
    internal fun activeProfile(): VehiclePropertyProfile? = service().activeProfile
        ?.takeIf { it.isNotBlank() }?.let(VehiclePropertyProfiles::decode)
    internal fun saveProfile(profile: VehiclePropertyProfile) { service().saveProfile(VehiclePropertyProfiles.encode(profile)) }
    internal fun readProfile(profile: VehiclePropertyProfile): Map<VehicleField, Double?> {
        val response = service().readProperties(VehiclePropertyProfiles.encode(profile))
        check(response.getInt("schema") == 1) { "车辆数据桥版本不兼容" }
        val values = response.getBundle("values") ?: Bundle()
        return profile.bindings.associate { binding -> binding.field to
            if (values.containsKey(binding.field.name)) values.getDouble(binding.field.name).takeIf { it.isFinite() } else null }
    }
    fun scanReport(): String {
        val descriptor = service().openProbeReport() ?: throw IllegalStateException("车辆数据桥没有返回扫描报告")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val bytes = stream.readBytesBounded(ReportPropertyImporter.MAX_BYTES)
            bytes.toString(Charsets.UTF_8).also { check(it.contains("schema=geely_property_probe_v2")) { "车辆扫描未完成，请查看车桥诊断" } }
        }
    }
    override fun close() { if (bound) { context.unbindService(connection); bound = false }; remote = null }

    companion object { const val BRIDGE_PACKAGE = "com.geely.desktop.vehicle.bridge" }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) return output.toByteArray()
        require(output.size() + size <= limit) { "扫描报告过大" }
        output.write(buffer, 0, size)
    }
}
