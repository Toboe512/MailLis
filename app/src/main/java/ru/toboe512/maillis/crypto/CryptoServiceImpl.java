package ru.toboe512.maillis.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;

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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoServiceImpl implements CryptoService {

    private static final byte[] CONTEXT_INFO = "MailLis_P2P_Protocol_v1".getBytes(UTF_8);// Контекст безопасности для расшифровки

    private static final String PREF_FILE_NAME = "maillis_crypto_prefs";
    private static final String KEYSET_NAME = "maillis_private_keyset";
    private static final String MASTER_KEY_URI = "android-keystore://maillis_master_key";// "android-keystore://maillis_master_key6ryfhh58gfhhwrrtyugfiytt74y5gjhl";
    private final KeysetHandle devicePrivateKeyHandle;
    private final KeysetHandle devicePublicKeyHandle;

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
    public String encrypt(String plainText, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException {
        byte[] plaintextBytes = plainText.getBytes(UTF_8);
        byte[] ciphertextBytes = encryptBytes(plaintextBytes, peerPublicKeyBytes);
        // Возвращаем Base64 строку для отправки по SMTP
        return Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP);
    }

    // --- ШАГ 2: ДЕШИФРОВАНИЕ ДЛЯ СЕБЯ (Используем Свой Приватный ключ из Keystore) ---
    public String decrypted(String base64Ciphertext) throws GeneralSecurityException, IOException {
        // Декодируем Base64 строку обратно в байты
        byte[] ciphertextBytes = Base64.decode(base64Ciphertext.trim(), Base64.NO_WRAP);
        byte[] decryptedBytes = decryptBytes(ciphertextBytes);
        return new String(decryptedBytes, UTF_8);
    }

    // --- НОВЫЙ МЕТОД 1: ШИФРОВАНИЕ МАССИВА БАЙТ ФОТОГРАФИИ ДЛЯ ДРУГА ---
    @Override
    public byte[] encryptBytes(byte[] rawImageBytes, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException {
        if (peerPublicKeyBytes == null || peerPublicKeyBytes.length == 0) {
            throw new GeneralSecurityException("Публичный ключ собеседника пуст или не получен!");
        }
        if (rawImageBytes == null || rawImageBytes.length == 0) {
            return null;
        }

        // Восстанавливаем публичный KeysetHandle собеседника из байт базы данных
        KeysetHandle peerPublicKeyHandle = CleartextKeysetHandle.read(
                BinaryKeysetReader.withBytes(peerPublicKeyBytes)
        );

        // Достаем примитив гибридного шифрования Tink
        HybridEncrypt hybridEncrypt = peerPublicKeyHandle.getPrimitive(HybridEncrypt.class);

        // Шифруем бинарный массив картинки асимметричным ключом Tink
        return hybridEncrypt.encrypt(rawImageBytes, CONTEXT_INFO);
    }

    // --- НОВЫЙ МЕТОД 2: ДЕШИФРОВАНИЕ МАССИВА БАЙТ ФОТОГРАФИИ ДЛЯ СЕБЯ ---
    @Override
    public byte[] decryptBytes(byte[] encryptedImageBytes) throws GeneralSecurityException {
        if (devicePrivateKeyHandle == null) {
            throw new GeneralSecurityException("Локальный приватный ключ не инициализирован!");
        }
        if (encryptedImageBytes == null || encryptedImageBytes.length == 0) {
            return null;
        }

        // Получаем примитив дешифрования из НАШЕГО локального приватного ключа
        HybridDecrypt hybridDecrypt = devicePrivateKeyHandle.getPrimitive(HybridDecrypt.class);

        // Расшифровываем бинарный массив фотографии
        return hybridDecrypt.decrypt(encryptedImageBytes, CONTEXT_INFO);
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

    /**
     * Генерирует надежный случайный симметричный секретный ключ для шифрования групп (AES-256).
     *
     * @return 32-байтный (256 бит) массив случайных байт
     */
    public static byte[] generateGroupSymmetricKey() {
        try {
            // Используем стандартный промышленный генератор ключей для алгоритма AES
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");

            // Инициализируем генератор криптографически стойким источником случайных чисел
            SecureRandom secureRandom = new SecureRandom();

            // Генерируем ключ длиной 256 бит (32 байта) — золотой стандарт безопасности
            keyGen.init(256, secureRandom);
            SecretKey secretKey = keyGen.generateKey();

            byte[] rawKeyBytes = secretKey.getEncoded();
            Log.d("MailLis_Crypto", "Успешно сгенерирован новый AES-256 ключ группы. Длина: " + rawKeyBytes.length + " байт.");

            return rawKeyBytes;
        } catch (Exception e) {
            Log.e("MailLis_Crypto", "Ошибка при генерации симметричного AES ключа", e);

            // Фолбэк на случай непредвиденного сбоя инициализации алгоритма
            byte[] fallbackKey = new byte[32];
            new SecureRandom().nextBytes(fallbackKey);
            return fallbackKey;
        }
    }

    public static byte[] encryptGroupText(String plainText, byte[] groupKey) throws Exception {
        return encryptGroupBytes(plainText.getBytes(UTF_8), groupKey);
    }

    public static byte[] encryptGroupBytes(byte[] payload, byte[] groupKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        var keySpec = new SecretKeySpec(groupKey, "AES");
        var spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        byte[] encrypted = cipher.doFinal(payload);

        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return result;
    }

    public static byte[] decryptGroupByte(byte[] encryptedWithIv, byte[] groupKey) throws Exception {
        if (encryptedWithIv == null || groupKey == null) {
            return null;
        }
        byte[] iv = new byte[12];

        // Извлекаем 12 байт вектора инициализации (IV) из начала массива
        System.arraycopy(encryptedWithIv, 0, iv, 0, iv.length);
        // Считаем размер зашифрованного контента
        int encryptedSize = encryptedWithIv.length - iv.length;
        byte[] payload = new byte[encryptedSize];

        // Переносим саму зашифрованную часть в массив payload
        System.arraycopy(encryptedWithIv, iv.length, payload, 0, encryptedSize);

        // Инициализируем стандартный шифр AES-GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        var keySpec = new SecretKeySpec(groupKey, "AES");
        var spec = new GCMParameterSpec(128, iv); // 128-битный тег аутентификации
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

        return cipher.doFinal(payload);
    }

    public static String decryptGroupText(byte[] encryptedWithIv, byte[] groupKey) throws Exception {
        if (encryptedWithIv == null || groupKey == null) {
            return EMPTI_STRING;
        }
        byte[] payload = CryptoServiceImpl.decryptGroupByte(encryptedWithIv, groupKey);

        return new String(payload, UTF_8);
    }
}
