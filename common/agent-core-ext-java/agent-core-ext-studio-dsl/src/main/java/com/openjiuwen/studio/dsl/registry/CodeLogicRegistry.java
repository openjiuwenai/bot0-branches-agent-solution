package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.studio.dsl.spi.CodeLogic;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class CodeLogicRegistry {
    private final Map<String, CodeLogic> byName = new ConcurrentHashMap<>();

    public void register(CodeLogic logic) {
        byName.put(logic.name(), logic);
    }

    public void loadServiceLoader() {
        for (CodeLogic logic : ServiceLoader.load(CodeLogic.class)) {
            register(logic);
        }
    }

    public Optional<CodeLogic> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
