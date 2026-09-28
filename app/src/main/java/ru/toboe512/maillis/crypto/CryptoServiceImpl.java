package ru.toboe512.maillis.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.crypto.tink.BinaryKeysetReader;
import com.google.crypto.tink.BinaryKeysetWriter;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.HybridEncrypt;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.hybrid.HybridConfig;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;

public class CryptoServiceImpl implements CryptoService {

    private static final String PREF_FILE_NAME = "maillis_crypto_prefs";
    private static final String KEYSET_NAME = "maillis_private_keyset";
    private static final String MASTER_KEY_URI = "android-keystore://maillis_master_key";// "android-keystore://maillis_master_key6ryfhh58gfhhwrrtyugfiytt74y5gjhl";
    private KeysetHandle devicePrivateKeyHandle;
    private KeysetHandle devicePublicKeyHandle;

    public CryptoServiceImpl(Context context) throws GeneralSecurityException, IOException {

        // 1. Регистрируем гибридное шифрование (включает ECDH и AES-GCM)
        HybridConfig.register();

        // AndroidKeysetManager САМ ищет ключ в SharedPreferences и восстанавливает его.
        // Если ключа там еще нет, .withKeyTemplate() указывает, какой ключ сгенерировать автоматически.
        AndroidKeysetManager keysetManager = new AndroidKeysetManager.Builder()
                .withSharedPref(context.getApplicationContext(), KEYSET_NAME, PREF_FILE_NAME)
                .withMasterKeyUri(MASTER_KEY_URI)
                .withKeyTemplate(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"))
                .build();

        // ВОССТАНОВЛЕНИЕ: Извлекаем уже сохраненный (или только что созданный) приватный ключ
        devicePrivateKeyHandle = keysetManager.getKeysetHandle();

        // Из восстановленного приватного ключа ВСЕГДА можно мгновенно получить публичный
        devicePublicKeyHandle = devicePrivateKeyHandle.getPublicKeysetHandle();

        Log.d("MailLis_Crypto", "Приватный ключ успешно восстановлен из хранилища Android!");

    }


    @Override
    // --- ШАГ 1: ШИФРОВАНИЕ ДЛЯ ДРУГА (Используем Публичный ключ собеседника из SQLite) ---
    public String encode(String plainText, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException {
        if (peerPublicKeyBytes == null || peerPublicKeyBytes.length == 0) {
            throw new GeneralSecurityException("Публичный ключ собеседника пуст или не получен!");
        }

        // Восстанавливаем публичный ключ собеседника из байт базы данных
        KeysetHandle peerPublicKeyHandle = CleartextKeysetHandle.read(
                BinaryKeysetReader.withBytes(peerPublicKeyBytes)
        );

        // Получаем примитив шифрования напрямую из ключа
        HybridEncrypt hybridEncrypt = peerPublicKeyHandle.getPrimitive(HybridEncrypt.class);

        byte[] plaintextBytes = plainText.getBytes(UTF_8);
        // Используем фиксированный маркер приложения как контекст безопасности (одинаковый везде!)
        byte[] contextInfo = "MailLis_P2P_Protocol_v1".getBytes(UTF_8);

        // Шифруем (внутри Tink сам проведет математику Диффи-Хеллмана)
        byte[] ciphertextBytes = hybridEncrypt.encrypt(plaintextBytes, contextInfo);

        // Возвращаем Base64 строку для отправки по SMTP
        return Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP);
    }


    // --- ШАГ 2: ДЕШИФРОВАНИЕ ДЛЯ СЕБЯ (Используем Свой Приватный ключ из Keystore) ---
    public String decode(String base64Ciphertext) throws GeneralSecurityException, IOException {
        if (devicePrivateKeyHandle == null) {
            throw new GeneralSecurityException("Локальный приватный ключ не инициализирован!");
        }

        // Получаем примитив дешифрования из НАШЕГО приватного ключа
        HybridDecrypt hybridDecrypt = devicePrivateKeyHandle.getPrimitive(HybridDecrypt.class);

        // Декодируем Base64 строку обратно в байты
        byte[] ciphertextBytes = Base64.decode(base64Ciphertext.trim(), Base64.NO_WRAP);
        byte[] contextInfo = "MailLis_P2P_Protocol_v1".getBytes(UTF_8);

        // Расшифровываем!
        byte[] decryptedBytes = hybridDecrypt.decrypt(ciphertextBytes, contextInfo);

        return new String(decryptedBytes, UTF_8);
    }

    // Метод для получения байт своего публичного ключа (для отправки при рукопожатии)
    public byte[] getMyPublicKeyBytes() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(
                devicePublicKeyHandle,
                BinaryKeysetWriter.withOutputStream(outputStream)
        );
        return outputStream.toByteArray();
    }

    @Override
    public String getMyPublicKeyBas64() throws IOException {
        return Base64.encodeToString(getMyPublicKeyBytes(), Base64.NO_WRAP);
    }
}
