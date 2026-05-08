package com.futureai.fdrs.layer2;

import com.ford.otx.command.Command;
import java.util.Map;

public interface CommandAdapter {
    String publicName();

    String commandClass();

    /** Describes the expected args map, for the /commands listing (docs). */
    Map<String, String> argShape();

    Command<?, ?, ?> make(Map<String, Object> args) throws Exception;

    Object serializeResult(Object result);
}
