package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetLastSelectedVehicles;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Collections;
import java.util.Map;

public final class GetLastSelectedVehiclesAdapter implements CommandAdapter {
    @Override public String publicName() { return "getLastSelectedVehicles"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetLastSelectedVehicles"; }
    @Override public Map<String, String> argShape() { return Collections.emptyMap(); }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetLastSelectedVehicles();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
