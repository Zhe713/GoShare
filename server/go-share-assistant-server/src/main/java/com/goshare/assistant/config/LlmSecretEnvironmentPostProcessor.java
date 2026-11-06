package com.goshare.assistant.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 把本地加密保存的 LLM 配置注入 Spring 环境。
 *
 * <p>为什么需要它：开源仓库不能带 key；而"每个使用者自己填 key 并存本地"如果存明文，
 * 一旦误提交/被顺手拷走就泄露。方案是：
 * <ul>
 *   <li>{@code ~/.goshare/goshare-llm.properties} —— 只存 {@code ENC:...} 密文（在仓库外）</li>
 *   <li>{@code ~/.goshare/master.key} —— 32 字节主密钥（与密文分文件存放）</li>
 *   <li>写入用 {@code node scripts/llm-key.mjs set}，交互式输入、不回显、不落明文</li>
 * </ul>
 *
 * <p>优先级：真实环境变量 {@code GOSHARE_LLM_API_KEY / GOSHARE_LLM_MODE} 最高（适合 CI / 生产），
 * 其次本地加密文件，最后才是 {@code application.yml} 里的默认值（mock）。
 * 解密失败不阻断启动，只降级为"未配置"并打 WARN —— 演示环境宁可少个功能，也不要起不来。
 */
public class LlmSecretEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SECRET_REL = ".goshare/goshare-llm.properties";
    private static final String MASTER_REL = ".goshare/master.key";
    private static final String SOURCE_NAME = "goshareLocalLlmSecrets";

    /** 由 Boot 注入 DeferredLogFactory：日志在 logging 初始化后才可输出，构造注入会自动回放 */
    private final Log log;

    public LlmSecretEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LlmSecretEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 1. 环境变量显式提供时以环境变量为准（容器/CI 场景），不读本地文件
        if (notBlank(System.getenv("GOSHARE_LLM_API_KEY"))) {
            return;
        }
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path secretFile = home.resolve(SECRET_REL);
        if (!Files.isReadable(secretFile)) {
            log.info("[llm-secret] 未发现本地密钥文件（" + secretFile + "），使用 application.yml 配置（默认 mock）");
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(secretFile)) {
            props.load(in);
        } catch (Exception e) {
            log.warn("[llm-secret] 读取本地配置失败：" + secretFile + " -> " + e.getMessage());
            return;
        }

        // 只有真的存在 ENC: 密文时才需要主密钥；纯明文配置（如 mode/http-url）不受影响
        boolean hasEncrypted = props.stringPropertyNames().stream()
                .anyMatch(n -> LlmSecretCrypto.isEncrypted(props.getProperty(n)));
        String masterKey = hasEncrypted ? resolveMasterKey(home) : null;
        if (hasEncrypted && masterKey == null) {
            log.warn("[llm-secret] 找不到主密钥（~/.goshare/master.key 或环境变量 GOSHARE_MASTER_KEY），"
                    + "本地 key 无法解密，将以 mock 模式运行。重新执行：node scripts/llm-key.mjs set");
            return;
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            if (LlmSecretCrypto.isEncrypted(value)) {
                try {
                    resolved.put(name, LlmSecretCrypto.decrypt(value, masterKey));
                } catch (Exception e) {
                    log.warn("[llm-secret] " + name + " 解密失败（主密钥可能已更换）：" + e.getMessage());
                }
            } else {
                resolved.put(name, value);
            }
        }
        if (resolved.isEmpty()) {
            return;
        }
        // 2. 插入到系统环境变量之后：覆盖 application.yml 的默认值，但不覆盖真实环境变量
        environment.getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(SOURCE_NAME, resolved));
        log.info("[llm-secret] 已加载本地加密 LLM 配置：mode=" + resolved.get("goshare.llm.mode")
                + "，api-key=" + (resolved.get("goshare.llm.api-key") == null ? "空" : "已解密"));
    }

    /** 主密钥：环境变量优先（方便 CI/多机），否则读本地文件 */
    private String resolveMasterKey(Path home) {
        String fromEnv = System.getenv("GOSHARE_MASTER_KEY");
        if (notBlank(fromEnv)) {
            return fromEnv.trim();
        }
        Path masterFile = home.resolve(MASTER_REL);
        if (!Files.isReadable(masterFile)) {
            return null;
        }
        try {
            String raw = Files.readString(masterFile).trim();
            return raw.isEmpty() ? null : raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Override
    public int getOrder() {
        // 在 ConfigData（加载 application.yml）之后执行，保证默认值已就位
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
