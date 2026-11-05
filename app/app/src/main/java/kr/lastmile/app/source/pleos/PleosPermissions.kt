package kr.lastmile.app.source.pleos

import android.content.Context
import android.content.pm.PackageManager

/** Pleos SDK 서비스는 권한이 없으면 바인더 스레드에서 SecurityException 을 던져 앱을 죽인다. 호출 전에 반드시 확인한다. */
object PleosPermissions {
    const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    const val CAR_DRIVE = "pleos.car.permission.CAR_DRIVE"       // Gear
    const val CAR_SAFETY = "pleos.car.permission.CAR_SAFETY"     // Telltales
    const val CAR_LIGHTS = "pleos.car.permission.CAR_LIGHTS"     // TurnSignal
    const val CAR_DOORS = "pleos.car.permission.CAR_DOORS"       // Door position (승하차)
    const val CAR_ENERGY = "pleos.car.permission.CAR_ENERGY"     // EvBattery SoC
    const val NAVI_ROUTE = "pleos.car.permission.NAVI_ROUTE"
    const val NAVI_ROUTE_SEARCH = "pleos.car.permission.NAVI_ROUTE_SEARCH"
    const val TTS = "pleos.car.permission.TTS_SERVICE"
    const val STT = "pleos.car.permission.STT_SERVICE"

    fun has(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, vararg permissions: String): List<String> = permissions.filterNot { has(context, it) }
}
