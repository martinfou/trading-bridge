package com.martinfou.trading.parser.bridge;

import java.util.List;

/** Named sqcli invocation from {@link SqJobScriptRegistry} (story 21-5). */
public record SqJobScript(
    String id,
    String description,
    List<String> args
) {
    public SqJobScript {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("args must not be empty");
        }
        args = List.copyOf(args);
        if (description == null) {
            description = "";
        }
    }
}
