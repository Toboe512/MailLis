package ru.toboe512.maillis.crypto;

import android.content.Context;

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
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;

public class CryptoServiceImpl implements CryptoService {

    private static final String PREF_FILE_NAME = "maillis_crypto_prefs";
    private static final String KEYSET_NAME = "maillis_private_keyset";
    private static final String MASTER_KEY_URI = "android-keystore://maillis_master_key6ryfhh58gfhhwrrtyugfiytt74y5gjhl";
    private KeysetHandle privateKeysetHandle;
    private KeysetHandle publicKeysetHandle;

    public CryptoServiceImpl(Context context, String chatId) throws GeneralSecurityException, IOException {

        // 1. Обязательно регистрируем конфигурацию гибридного шифрования Tink
        HybridConfig.register();

        // 2. Инициализируем менеджер ключей Tink для Android
        AndroidKeysetManager keysetManager = new AndroidKeysetManager.Builder()
                .withSharedPref(context.getApplicationContext(), KEYSET_NAME + chatId, PREF_FILE_NAME)
                .withMasterKeyUri(MASTER_KEY_URI) // Ключ шифрования самого хранилища
                .withKeyTemplate(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM")) // Шаблон нашего P2P-ключа
                .build();

        // 3. Получаем наш сохраненный (или только что созданный) приватный ключ
        privateKeysetHandle = keysetManager.getKeysetHandle();

        // Получаем открытый ключ, который мы ОПРАВИМ другому пользователю по P2P
        publicKeysetHandle = privateKeysetHandle.getPublicKeysetHandle();
    }

    @Override
    public String decode(byte[] ciphertext, String chatId) throws GeneralSecurityException, UnsupportedEncodingException {
        // Получаем инструмент для расшифровки из своего приватного ключа
        // ДЛЯ РАСШИФРОВКИ (используем ваш приватный ключ)
        // Вместо HybridDecryptFactory.getPrimitive(privateKeysetHandle) пишем:
        HybridDecrypt hybridDecrypt = privateKeysetHandle.getPrimitive(HybridDecrypt.class);

        // contextInfo — это любая строка (например, ID чата), гарантирующая, что сообщение не перехватили и не переставили в другой чат
        byte[] contextInfo = chatId.getBytes("UTF-8");
        // Расшифровываем (обязательно передаем тот же contextInfo)
        byte[] decryptedBytes = hybridDecrypt.decrypt(ciphertext, contextInfo);

        return new String(decryptedBytes, "UTF-8"); // "Привет, это секретное P2P сообщение!"
    }

    @Override
    public byte[] encode(String txt, String chatId, byte[] publicKey) throws GeneralSecurityException, IOException {
        byte[] plaintext;
        byte[] ciphertext;

        // Читаем байты и собираем из них обратно KeysetHandle публичного ключа
        KeysetHandle peerPublicKeyHandle = CleartextKeysetHandle.read(
                BinaryKeysetReader.withBytes(publicKey)
        );

        // Всё! Теперь этот ключ можно сразу использовать для шифрования, как в ЧАСТИ 1:
        // Получаем инструмент для шифрования из публичного ключа собеседника
        // ДЛЯ ШИФРОВАНИЯ (нужен публичный ключ):
        HybridEncrypt hybridEncrypt = peerPublicKeyHandle.getPrimitive(HybridEncrypt.class);


        plaintext = txt.getBytes("UTF-8");

        // contextInfo — это любая строка (например, ID чата), гарантирующая, что сообщение не перехватили и не переставили в другой чат
        byte[] contextInfo = chatId.getBytes("UTF-8");

        // Шифруем
        ciphertext = hybridEncrypt.encrypt(plaintext, contextInfo);

        // Полученный массив байт ciphertext можно смело отправлять по сети!
        return ciphertext;
    }

    public byte[] getPublicKeyToBytes() throws IOException {
        // Пишем его в поток байт
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(
                publicKeysetHandle,
                BinaryKeysetWriter.withOutputStream(outputStream)
        );

        // Получаем чистый byte[], который отправляем по P2P-сети
        return outputStream.toByteArray();
    }

    public byte[] getPrivateKeyToBytes() throws IOException {
        // Пишем его в поток байт
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(
                privateKeysetHandle,
                BinaryKeysetWriter.withOutputStream(outputStream)
        );

        // Получаем чистый byte[], который отправляем по P2P-сети
        return outputStream.toByteArray();
    }
}
