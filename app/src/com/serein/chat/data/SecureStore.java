package com.serein.chat.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密本地敏感数据（服务商与 API Key）。
 * 密钥不可导出，只在本应用进程中可用；卸载应用即销毁。
 * 密文格式：base64(IV 12B || ciphertext+tag)。
 */
public final class SecureStore {

    private static final String ALIAS = "app_secure_v1";
    private static final String PREFIX = "enc1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecureStore() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** 加密为可直接存 SharedPreferences 的字符串；失败返回原文（仅本地降级，不写日志）。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain;
        }
    }

    /** 解密；非密文（旧版本明文数据）原样返回，便于平滑迁移。 */
    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
