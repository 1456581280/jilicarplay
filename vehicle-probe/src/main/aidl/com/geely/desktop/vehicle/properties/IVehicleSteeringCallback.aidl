// carlito | Public steering events; OEM implementations remain inside the vehicle bridge APK.
package com.geely.desktop.vehicle.properties;
import android.os.Bundle;
oneway interface IVehicleSteeringCallback {
    void onKeyEvent(in Bundle event);
}
