package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetCdlHistory;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReadVehicleHistoryAdapter implements CommandAdapter {
    @Override public String publicName() { return "readVehicleHistory"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetCdlHistory"; }

    @Override
    public Map<String, String> argShape() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("vin", "string (optional) — VIN to scope the history lookup. Defaults to empty string; Ford command's semantics for empty-VIN pending Part-2 empirical test.");
        return s;
    }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        Object vinArg = args.get("vin");
        String vin = vinArg instanceof String ? (String) vinArg : "";
        return new GetCdlHistory(vin);
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
