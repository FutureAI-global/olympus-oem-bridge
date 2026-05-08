package com.futureai.fdrs.layer2;

import com.futureai.fdrs.layer2.adapter.GetApplicationsAndSystemsAdapter;
import com.futureai.fdrs.layer2.adapter.GetEngineeringDataAdapter;
import com.futureai.fdrs.layer2.adapter.GetInstalledMeasurementDevicesAdapter;
import com.futureai.fdrs.layer2.adapter.GetLastSelectedVehiclesAdapter;
import com.futureai.fdrs.layer2.adapter.GetMeasurementDeviceAdapter;
import com.futureai.fdrs.layer2.adapter.GetNodeToMdxMappingAdapter;
import com.futureai.fdrs.layer2.adapter.GetServiceBulletinsForDTCsAdapter;
import com.futureai.fdrs.layer2.adapter.GetVehicleModelStatusAdapter;
import com.futureai.fdrs.layer2.adapter.ListModulesAdapter;
import com.futureai.fdrs.layer2.adapter.PerformVehicleIDProcessAdapter;
import com.futureai.fdrs.layer2.adapter.ReadDIDAdapter;
import com.futureai.fdrs.layer2.adapter.ReadDIDBatchAdapter;
import com.futureai.fdrs.layer2.adapter.ReadSelfTestDTCsAdapter;
import com.futureai.fdrs.layer2.adapter.ReadVehicleHistoryAdapter;
import com.futureai.fdrs.layer2.adapter.RetrieveNetworkTestApplicationsAdapter;
import com.futureai.fdrs.layer2.adapter.GetLastNetworkTestRanAdapter;
import com.futureai.fdrs.layer2.adapter.CheckNetworkTestRanAdapter;
import com.futureai.fdrs.layer2.adapter.GetCurrentVinAdapter;
import com.futureai.fdrs.layer2.adapter.IsCheckVinRequiredAdapter;
import com.futureai.fdrs.layer2.adapter.IsEvisDataExpiredAdapter;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class CommandAdapterRegistry {
    private final Map<String, CommandAdapter> byPublicName = new LinkedHashMap<>();

    public CommandAdapterRegistry() {
        register(new GetLastSelectedVehiclesAdapter());
        register(new GetApplicationsAndSystemsAdapter());
        register(new GetVehicleModelStatusAdapter());
        register(new GetInstalledMeasurementDevicesAdapter());
        register(new GetMeasurementDeviceAdapter());
        register(new ListModulesAdapter());
        register(new ReadSelfTestDTCsAdapter());
        register(new ReadVehicleHistoryAdapter());
        register(new ReadDIDAdapter());
        register(new ReadDIDBatchAdapter());
        register(new GetEngineeringDataAdapter());
        register(new GetNodeToMdxMappingAdapter());
        register(new GetServiceBulletinsForDTCsAdapter());
        register(new RetrieveNetworkTestApplicationsAdapter());
        register(new GetLastNetworkTestRanAdapter());
        register(new CheckNetworkTestRanAdapter());
        register(new PerformVehicleIDProcessAdapter());
        register(new GetCurrentVinAdapter());
        register(new IsCheckVinRequiredAdapter());
        register(new IsEvisDataExpiredAdapter());
    }

    private void register(CommandAdapter a) {
        if (byPublicName.put(a.publicName(), a) != null) {
            throw new IllegalStateException("duplicate adapter publicName: " + a.publicName());
        }
    }

    public CommandAdapter getByPublicName(String name) {
        return byPublicName.get(name);
    }

    public Collection<CommandAdapter> all() {
        return Collections.unmodifiableCollection(byPublicName.values());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byPublicName.keySet());
    }
}
