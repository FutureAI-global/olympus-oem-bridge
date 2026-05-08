package com.futureai.fdrs.layer2.adapter;

import com.ford.etis.runtime.measurement.management.command.GetInstalledMeasurementDevices;
import com.ford.otx.command.Command;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Collections;
import java.util.Map;

public final class GetInstalledMeasurementDevicesAdapter implements CommandAdapter {
    @Override public String publicName() { return "getInstalledMeasurementDevices"; }
    @Override public String commandClass() { return "com.ford.etis.runtime.measurement.management.command.GetInstalledMeasurementDevices"; }
    @Override public Map<String, String> argShape() { return Collections.emptyMap(); }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetInstalledMeasurementDevices();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
