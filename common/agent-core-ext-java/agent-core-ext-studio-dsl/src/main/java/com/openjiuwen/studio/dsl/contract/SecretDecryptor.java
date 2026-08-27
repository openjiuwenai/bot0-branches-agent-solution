/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

/**
 * Decrypts SECRET params from OBS connection files (Python {@code CryptTool.decrypt}).
 *
 * @since 2026-08-26
 */

@FunctionalInterface
public interface SecretDecryptor {

    /**
     * decrypt.
     *
     * @param ciphertext ciphertext from OBS
     * @return plaintext secret
     */

    String decrypt(String ciphertext);
}
