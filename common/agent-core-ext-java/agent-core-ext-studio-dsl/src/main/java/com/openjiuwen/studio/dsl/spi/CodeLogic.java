package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/** Java SPI for jiuwen.code (FEAT-031 MUST). */
public interface CodeLogic {
    String name();

    Map<String, Object> execute(Map<String, Object> inputs, CodeLogicContext ctx) throws Exception;
}
