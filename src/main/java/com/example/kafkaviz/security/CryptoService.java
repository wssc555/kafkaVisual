package com.example.kafkaviz.security;

import com.example.kafkaviz.storage.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密服务(AES-GCM)。
 *
 * <p>适用两处凭据:① Kafka 集群 SASL 口令({@code cluster_config.password_cipher});
 * ② 外部数据源口令({@code storage.json} 的 {@code passwordCipher})。
 *
 * <p><b>防护口径(明确记录,不做过度承诺)</b>:防的是数据库文件 / 配置文件被单独拷贝走后
 * <b>离线读出明文</b>。它<b>不防</b>本机定向攻击 —— 攻击者若能同时拿到 {@code secret.key}
 * 与密文,就能解密。桌面工具场景下此取舍可接受。
 *
 * <p>密文格式:{@code Base64( IV(12 字节) || ciphertext || GCM tag(16 字节) )}。
 * IV 每加密一次随机生成并前置,同一明文两次加密结果不同。
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    /** AES-256。 */
    private static final int KEY_BYTES = 32;
    /** GCM 推荐 IV 长度,固定 12 字节(与 tag 一起构成 96+128 的标准组合)。 */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppPaths appPaths) {
        this.key = loadOrCreateKey(appPaths.secretKeyFile());
    }

    /**
     * 加密明文口令。
     *
     * @return Base64 密文;null / 空串直通返回空串(语义:无口令,不是"空口令密文")
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 加密失败属服务端配置/环境问题(如 JCE 受限),不能让调用方拿到"貌似成功"的空值
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /**
     * 解密密文。
     *
     * @return 明文;null / 空串直通返回空串
     * @throws IllegalStateException 密文损坏、密钥不匹配(换过 secret.key)或格式非法
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return "";
        }
        try {
            byte[] raw = Base64.getDecoder().decode(cipherText);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt credential (corrupted ciphertext or replaced secret.key)", e);
        }
    }

    /** 密文是否可解(用于自检/诊断,不抛异常)。 */
    public boolean canDecrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return true;
        }
        try {
            decrypt(cipherText);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 读取 {@code secret.key};不存在则生成 32 字节随机密钥。
     *
     * <p>生成后把文件权限收紧到"仅属主可读写"(POSIX 0600 / Windows ACL 尽力而为):
     * 这只是一层纵深防御,真正保证的是磁盘加密与操作系统账户隔离。
     */
    private SecretKeySpec loadOrCreateKey(Path keyFile) {
        byte[] material = readExistingKey(keyFile);
        if (material == null) {
            material = generateAndPersistKey(keyFile);
        }
        if (material.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "Invalid secret.key at " + keyFile + ": expected " + KEY_BYTES
                            + " bytes but found " + material.length
                            + ". Delete the file to regenerate (existing ciphertexts will become unreadable).");
        }
        return new SecretKeySpec(material, KEY_ALGORITHM);
    }

    private byte[] readExistingKey(Path keyFile) {
        if (!Files.exists(keyFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read secret.key at " + keyFile, e);
        }
    }

    private byte[] generateAndPersistKey(Path keyFile) {
        byte[] material = new byte[KEY_BYTES];
        random.nextBytes(material);
        try {
            // CREATE_NEW:并发场景下宁可失败也不能覆盖对方刚写好的密钥
            Files.write(keyFile, material, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            log.info("Generated new credential encryption key at {}", keyFile);
        } catch (FileAlreadyExistsException e) {
            // 并发/双实例:另一进程先写好了,复用它(不能覆盖,否则对方刚写的密文全废)
            byte[] existing = readExistingKey(keyFile);
            if (existing != null) {
                return existing;
            }
            throw new IllegalStateException("Failed to create secret.key at " + keyFile, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write secret.key at " + keyFile, e);
        }
        restrictPermissionsBestEffort(keyFile);
        return material;
    }

    /**
     * 收紧密钥文件权限,尽力而为。
     *
     * <p>POSIX 下走 {@code Files.setPosixFilePermissions};Windows 无 POSIX 视图,
     * 退化为 ACL(此处不引入额外的 ACL 库,只记录一条 debug 日志)——
     * 权限收紧失败不阻断启动,因为防护口径本就不覆盖本机定向攻击。
     */
    private void restrictPermissionsBestEffort(Path keyFile) {
        try {
            var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(keyFile, perms);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("POSIX permissions not applied to {} (non-POSIX filesystem): {}",
                    keyFile, e.getMessage());
        }
    }
}
