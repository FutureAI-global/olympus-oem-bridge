package com.futureai.fdrs.layer2.adapter;

import com.ford.etis.runtime.measurement.management.command.GetMeasurementDevice;
import com.ford.otx.command.Command;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GetMeasurementDeviceAdapter implements CommandAdapter {
    @Override public String publicName() { return "getMeasurementDevice"; }
    @Override public String commandClass() { return "com.ford.etis.runtime.measurement.management.command.GetMeasurementDevice"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("deviceId", "string (required) — measurement device id as returned by getInstalledMeasurementDevices");
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        Object id = args.get("deviceId");
        if (!(id instanceof String) || ((String) id).isEmpty()) {
            throw new IllegalArgumentException("deviceId (string) is required");
        }
        return new GetMeasurementDevice((String) id);
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
