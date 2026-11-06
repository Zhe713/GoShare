package com.goshare.assistant.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地密钥加解密：与 scripts/llm-key.mjs 的 Node 实现必须互通。
 * 重点：往返一致、密文不含明文、错误主密钥必须失败（不能静默返回脏数据）。
 */
class LlmSecretCryptoTest {

    private static final String MASTER = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripKeepsPlaintext() {
        String plain = "sk-ws-demo-key-abcdefg";
        String enc = LlmSecretCrypto.encrypt(plain, MASTER);

        assertTrue(enc.startsWith(LlmSecretCrypto.PREFIX));
        assertFalse(enc.contains(plain), "密文里不能出现明文");
        assertEquals(plain, LlmSecretCrypto.decrypt(enc, MASTER));
    }

    @Test
    void eachEncryptionUsesFreshIv() {
        String plain = "same-key";
        assertFalse(LlmSecretCrypto.encrypt(plain, MASTER).equals(LlmSecretCrypto.encrypt(plain, MASTER)),
                "相同明文两次加密结果必须不同（随机 IV）");
    }

    @Test
    void wrongMasterKeyFails() {
        String enc = LlmSecretCrypto.encrypt("sk-demo", MASTER);
        String otherMaster = Base64.getEncoder()
                .encodeToString("ffffffffffffffffffffffffffffffff".getBytes());

        assertThrows(IllegalStateException.class, () -> LlmSecretCrypto.decrypt(enc, otherMaster));
    }

    @Test
    void plaintextValuePassesThrough() {
        assertEquals("plain-value", LlmSecretCrypto.decrypt("plain-value", MASTER));
        assertFalse(LlmSecretCrypto.isEncrypted("plain-value"));
        assertTrue(LlmSecretCrypto.isEncrypted("ENC:abc"));
    }

    @Test
    void masterKeyMustBe32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes());
        assertThrows(IllegalArgumentException.class, () -> LlmSecretCrypto.encrypt("x", shortKey));
    }
}
