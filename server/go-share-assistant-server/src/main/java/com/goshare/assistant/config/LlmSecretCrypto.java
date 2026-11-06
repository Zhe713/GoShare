package com.goshare.assistant.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * LLM 密钥本地加密存储（AES-256-GCM）。
 *
 * <p>设计目标：让每个使用者把自己的大模型 key 存在**本机**、**加密**、**不进仓库**。
 * 密文格式：{@code ENC:} + base64(iv[12] | tag[16] | ciphertext)，
 * 主密钥为 32 字节随机数（base64 存放于 {@code ~/.goshare/master.key}，与密文分文件）。
 *
 * <p>与 {@code scripts/llm-key.mjs} 严格对齐（Node 侧同样用 AES-256-GCM + 12 字节 IV + 16 字节 tag），
 * 两侧可以互相解密；改格式必须同时改两处。
 *
 * <p>安全边界（诚实说明）：这是"防止明文 key 被顺手抄走/误提交"的本地防护，
 * 不是对抗本机管理员或恶意软件的方案；生产环境请用 KMS / 密钥管理服务。
 */
public final class LlmSecretCrypto {

    /** 密文前缀，便于识别"这是需要解密的字段" */
    public static final String PREFIX = "ENC:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_LEN = TAG_BITS / 8;

    private LlmSecretCrypto() {
    }

    /** 是否为本工具生成的密文 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 解密 {@code ENC:...}。
     *
     * @param encrypted 带前缀的密文
     * @param masterKeyBase64 32 字节主密钥的 base64
     * @throws IllegalStateException 密钥或密文不合法（调用方应降级为"未配置"而不是启动失败）
     */
    public static String decrypt(String encrypted, String masterKeyBase64) {
        if (!isEncrypted(encrypted)) {
            return encrypted;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            if (raw.length <= IV_LEN + TAG_LEN) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            byte[] iv = new byte[IV_LEN];
            byte[] tag = new byte[TAG_LEN];
            byte[] ct = new byte[raw.length - IV_LEN - TAG_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            System.arraycopy(raw, IV_LEN, tag, 0, TAG_LEN);
            System.arraycopy(raw, IV_LEN + TAG_LEN, ct, 0, ct.length);

            // Java GCM 期望 tag 追加在密文尾部
            byte[] ctWithTag = new byte[ct.length + TAG_LEN];
            System.arraycopy(ct, 0, ctWithTag, 0, ct.length);
            System.arraycopy(tag, 0, ctWithTag, ct.length, TAG_LEN);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(masterKeyBase64), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 密钥解密失败（主密钥不匹配或密文损坏）", e);
        }
    }

    /** 加密明文（给测试与后续运维工具用；日常由 scripts/llm-key.mjs 生成） */
    public static String encrypt(String plain, String masterKeyBase64) {
        // 主密钥非法属于"使用者配置错误"，直接抛 IllegalArgumentException（不要包成加解密失败）
        SecretKeySpec spec = key(masterKeyBase64);
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, spec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // Java 输出 = ciphertext||tag
            byte[] ct = new byte[out.length - TAG_LEN];
            byte[] tag = new byte[TAG_LEN];
            System.arraycopy(out, 0, ct, 0, ct.length);
            System.arraycopy(out, ct.length, tag, 0, TAG_LEN);

            byte[] raw = new byte[IV_LEN + TAG_LEN + ct.length];
            System.arraycopy(iv, 0, raw, 0, IV_LEN);
            System.arraycopy(tag, 0, raw, IV_LEN, TAG_LEN);
            System.arraycopy(ct, 0, raw, IV_LEN + TAG_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 密钥加密失败", e);
        }
    }

    private static SecretKeySpec key(String masterKeyBase64) {
        byte[] k = Base64.getDecoder().decode(masterKeyBase64.trim());
        if (k.length != 32) {
            throw new IllegalArgumentException("主密钥必须是 32 字节（AES-256）");
        }
        return new SecretKeySpec(k, "AES");
    }
}
