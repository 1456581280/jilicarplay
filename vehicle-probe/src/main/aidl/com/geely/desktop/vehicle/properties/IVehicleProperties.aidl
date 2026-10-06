// carlito | Public property and steering boundary between the vehicle bridge and DiPlay.
package com.geely.desktop.vehicle.properties;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.geely.desktop.vehicle.properties.IVehicleSteeringCallback;

interface IVehicleProperties {
    Bundle getStatus();
    String[] getModelIds();
    String getPreset(String modelId);
    String getActiveProfile();
    void saveProfile(String profileJson);
    Bundle readProperties(String profileJson);
    ParcelFileDescriptor openProbeReport();
    // carlito | Append-only. A callback Binder owns the lease and releases it on death.
    Bundle registerSteeringListener(IVehicleSteeringCallback callback, in int[] keyCodes, boolean intercept);
    void unregisterSteeringListener(IVehicleSteeringCallback callback);
    Bundle getSteeringStatus();
    // carlito | Append-only instrument lease; entry is permitted only after the client renders a frame.
    Bundle updateProjection(IBinder owner, boolean frameReady);
    Bundle releaseProjection(IBinder owner);
    Bundle getProjectionStatus();
}
