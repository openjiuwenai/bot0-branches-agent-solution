/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.stream;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 从各 Skill 的 SKILL.yaml 收集业务话术。
 *
 * 对齐 Python 解耦版 agent_rule.py collect_skill_scripts()。
 * V2 优化：从 SKILL.yaml（纯 YAML）收集 scripts 字段。
 *
 * @since 2024-01-01
 */
public class SkillScriptsCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillScriptsCollector.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 从 skills 目录收集所有 SKILL.yaml 中的 scripts 字段。
     *
     * @param skillsDir skills 根目录
     * @return 话术字典，key 为话术名，value 为话术内容
     */
    public static Map<String, String> collectSkillScripts(Path skillsDir) {
        Map<String, String> scripts = new HashMap<>();
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            LOGGER.info("SkillScriptsCollector: skills dir not found, skipping");
            return scripts;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path skillDir : stream) {
                if (!Files.isDirectory(skillDir))
                    continue;
                Path v1Dir = skillDir.resolve("v1");
                Path skillYaml = (Files.exists(v1Dir)) ? v1Dir.resolve("SKILL.yaml") : skillDir.resolve("SKILL.yaml");

                if (!Files.exists(skillYaml))
                    continue;

                Map<String, Object> parsed = YAML_MAPPER.readValue(Files.readString(skillYaml), Map.class);
                Object scriptsObj = parsed.get("scripts");
                if (scriptsObj instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) scriptsObj).entrySet()) {
                        if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                            scripts.put((String) entry.getKey(), (String) entry.getValue());
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("SkillScriptsCollector: failed to scan skills dir: {}", e.getMessage());
        }

        LOGGER.info("SkillScriptsCollector: collected {} skill scripts", scripts.size());
        return scripts;
    }
}
