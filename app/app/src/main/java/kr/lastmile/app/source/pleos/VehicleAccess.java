package kr.lastmile.app.source.pleos;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.Brake;
import ai.pleos.playground.vehicle.api.Door;
import ai.pleos.playground.vehicle.api.EvBattery;
import ai.pleos.playground.vehicle.api.Gear;
import ai.pleos.playground.vehicle.api.Safety;

/** Kotlin 에서는 Vehicle 의 도메인 프로퍼티가 private 로 보이므로(메타데이터), Java 의 public getter 로 우회한다. */
final class VehicleAccess {
    private VehicleAccess() {}
    static Gear gear(Vehicle v) { return v.getGear(); }
    static Brake brake(Vehicle v) { return v.getBrake(); }
    static Safety safety(Vehicle v) { return v.getSafety(); }
    static Door door(Vehicle v) { return v.getDoor(); }
    static EvBattery evBattery(Vehicle v) { return v.getEvBattery(); }
}
