package com.futureai.fdrs.layer2.adapter;

import com.ford.otx.command.Command;
import com.ford.otx.services.vehicle.command.GetVehicleModelStatus;
import com.futureai.fdrs.layer2.CommandAdapter;
import com.futureai.fdrs.layer2.Json;
import java.util.Collections;
import java.util.Map;

public final class GetVehicleModelStatusAdapter implements CommandAdapter {
    @Override public String publicName() { return "getVehicleModelStatus"; }
    @Override public String commandClass() { return "com.ford.otx.services.vehicle.command.GetVehicleModelStatus"; }
    @Override public Map<String, String> argShape() { return Collections.emptyMap(); }

    @Override
    public Command<?, ?, ?> make(Map<String, Object> args) {
        return new GetVehicleModelStatus();
    }

    @Override
    public Object serializeResult(Object result) {
        return Json.walkBean(result, 6);
    }
}
