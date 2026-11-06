package com.goshare.assistant.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地加密 key 的加载逻辑：只有「文件存在 + 主密钥匹配」才注入，任何异常都应降级而不是启动失败。
 */
class LlmSecretEnvironmentPostProcessorTest {

    private Path home;
    private String originalHome;

    @BeforeEach
    void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        home = Files.createTempDirectory("goshare-home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    private LlmSecretEnvironmentPostProcessor processor() {
        DeferredLogFactory factory = mock(DeferredLogFactory.class);
        when(factory.getLog(any(Class.class))).thenReturn(mock(org.apache.commons.logging.Log.class));
        return new LlmSecretEnvironmentPostProcessor(factory);
    }

    private void writeSecret(String apiKeyLine) throws Exception {
        Path dir = home.resolve(".goshare");
        Files.createDirectories(dir);
        String content = "goshare.llm.mode=http\n"
                + "goshare.llm.api-key=" + apiKeyLine + "\n";
        Files.writeString(dir.resolve("goshare-llm.properties"), content, StandardCharsets.UTF_8);
    }

    private String masterKey() throws Exception {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        String b64 = Base64.getEncoder().encodeToString(raw);
        Path dir = home.resolve(".goshare");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("master.key"), b64 + "\n", StandardCharsets.UTF_8);
        return b64;
    }

    @Test
    void skipsWhenNoSecretFile() {
        StandardEnvironment env = new StandardEnvironment();
        processor().postProcessEnvironment(env, null);

        assertNull(env.getProperty("goshare.llm.api-key"));
    }

    @Test
    void skipsWhenMasterKeyMissing() throws Exception {
        writeSecret("ENC:abcdef");
        StandardEnvironment env = new StandardEnvironment();

        processor().postProcessEnvironment(env, null);

        // 没有主密钥就当作"未配置"，不能让服务起不来，也不能把密文当明文塞进环境
        assertNull(env.getProperty("goshare.llm.api-key"));
    }

    @Test
    void loadsEncryptedKeyWhenMasterMatches() throws Exception {
        String master = masterKey();
        String cipher = LlmSecretCrypto.encrypt("sk-local-test-key", master);
        writeSecret(cipher);
        StandardEnvironment env = new StandardEnvironment();

        processor().postProcessEnvironment(env, null);

        assertEquals("http", env.getProperty("goshare.llm.mode"));
        assertEquals("sk-local-test-key", env.getProperty("goshare.llm.api-key"));
    }

    @Test
    void loadsPlainValueForNonEncryptedConfig() throws Exception {
        writeSecret("sk-plain-value");
        StandardEnvironment env = new StandardEnvironment();

        processor().postProcessEnvironment(env, null);

        assertEquals("sk-plain-value", env.getProperty("goshare.llm.api-key"));
    }

    @Test
    void wrongMasterKeyDoesNotInject() throws Exception {
        masterKey();
        // 用别的主密钥加密 → 解密必须失败，并且不注入脏数据
        String otherMaster = Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());
        writeSecret(LlmSecretCrypto.encrypt("sk-x", otherMaster));
        StandardEnvironment env = new StandardEnvironment();

        processor().postProcessEnvironment(env, null);

        assertNull(env.getProperty("goshare.llm.api-key"));
        assertNotNull(env.getProperty("goshare.llm.mode", "http")); // mode 仍来自配置文件普通值
    }
}
