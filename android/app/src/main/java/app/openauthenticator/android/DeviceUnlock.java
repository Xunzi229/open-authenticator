package app.openauthenticator.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.biometric.BiometricManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local encrypted credentials. These values are never included in WebDAV data. */
final class DeviceUnlock {
    private static final String PREFS = "device_unlock";
    private static final String DIRECT_ALIAS = "openauthenticator_direct_v1";
    private static final String BIOMETRIC_ALIAS = "openauthenticator_biometric_v1";
    private static final String DIRECT = "direct";
    private static final String BIOMETRIC = "biometric";
    private final SharedPreferences prefs;

    DeviceUnlock(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    boolean directEnabled() { return prefs.contains(DIRECT); }
    boolean biometricEnabled() { return prefs.contains(BIOMETRIC); }

    boolean biometricAvailable(Context context) {
        return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    void enableDirect(String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(DIRECT_ALIAS, false));
        prefs.edit().putString(DIRECT, seal(cipher, password)).commit();
    }

    String directPassword() throws Exception {
        return open(DIRECT_ALIAS, prefs.getString(DIRECT, null));
    }

    Cipher biometricEncryptCipher() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(BIOMETRIC_ALIAS, true));
        return cipher;
    }

    void saveBiometric(Cipher authenticatedCipher, String password) throws Exception {
        prefs.edit().putString(BIOMETRIC, seal(authenticatedCipher, password)).commit();
    }

    Cipher biometricDecryptCipher() throws Exception {
        String value = prefs.getString(BIOMETRIC, null);
        if (value == null) throw new IllegalStateException("指纹解锁尚未启用");
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("指纹凭据已损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(BIOMETRIC_ALIAS, true),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return cipher;
    }

    String biometricPassword(Cipher authenticatedCipher) throws Exception {
        String[] parts = prefs.getString(BIOMETRIC, "").split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("指纹凭据已损坏");
        byte[] decoded = authenticatedCipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        return new String(decoded, StandardCharsets.UTF_8);
    }

    void disableDirect() { prefs.edit().remove(DIRECT).commit(); deleteKey(DIRECT_ALIAS); }
    void disableBiometric() { prefs.edit().remove(BIOMETRIC).commit(); deleteKey(BIOMETRIC_ALIAS); }
    void clear() { disableDirect(); disableBiometric(); }

    private String seal(Cipher cipher, String password) throws Exception {
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String open(String alias, String value) throws Exception {
        if (value == null) throw new IllegalStateException("免密码进入尚未启用");
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("免密码凭据已损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(alias, false),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey key(String alias, boolean biometric) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(alias, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE);
        if (biometric) {
            spec.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            else spec.setUserAuthenticationValidityDurationSeconds(-1);
        }
        generator.init(spec.build());
        return generator.generateKey();
    }

    private void deleteKey(String alias) {
        try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); }
        catch (Exception ignored) { }
    }
}
