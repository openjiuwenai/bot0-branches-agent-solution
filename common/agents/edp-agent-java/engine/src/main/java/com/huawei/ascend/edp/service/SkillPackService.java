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

package com.huawei.ascend.edp.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 技能包打包服务 -- 将 skillsDir 打包为 tar.gz 供沙箱部署使用。
 *
 * <p>对应特性：5.2.1（自动打包）+ 5.2.2（自动上传）+ 5.2.3（自动解压）+ 5.2.4（解压目录可配置）</p>
 *
 * <p>打包流程：SkillsPackService.packSkills() → SysOperation.fs().uploadFile() → SysOperation.shell().executeCmd("tar -xzf ...")</p>
 *
 * @since 2024-01-01
 */
public class SkillPackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillPackService.class);

    /**
     * 将 skillsDir 打包为 tar.gz 临时文件。
     *
     * @param skillsDir 技能目录路径
     * @return 生成的 tar.gz 临时文件路径
     * @throws IOException 打包失败时抛出
     */
    public Path packSkills(Path skillsDir) throws IOException {
        if (skillsDir == null || !Files.exists(skillsDir)) {
            throw new IOException("Skills directory not found: " + skillsDir);
        }

        Path tarGz = Files.createTempFile("edp-skills-", ".tar.gz");
        try (OutputStream fos = Files.newOutputStream(tarGz);
                OutputStream gos = new GzipCompressorOutputStream(fos);
                TarArchiveOutputStream tos = new TarArchiveOutputStream(gos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            try (Stream<Path> walk = Files.walk(skillsDir)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String entryName = skillsDir.relativize(path).toString().replace('\\', '/');
                        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
                        tos.putArchiveEntry(entry);
                        Files.copy(path, tos);
                        tos.closeArchiveEntry();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to add file to tar: " + path, e);
                    }
                });
            }
        } catch (IOException e) {
            // 打包失败时清理临时文件，避免泄漏
            try {
                Files.deleteIfExists(tarGz);
            } catch (IOException ignored) {
            }
            throw e;
        }
        LOGGER.info("[EDP-SANDBOX] Skills packed: {} → {} ({} bytes)", skillsDir, tarGz, Files.size(tarGz));
        return tarGz;
    }

    /**
     * 清理临时打包文件。
     *
     * @param tarGz 临时文件路径
     */
    public void cleanup(Path tarGz) {
        if (tarGz != null) {
            try {
                Files.deleteIfExists(tarGz);
                LOGGER.debug("[EDP-SANDBOX] Cleaned up temp file: {}", tarGz);
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
        }
    }
}
