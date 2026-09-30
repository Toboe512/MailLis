package ru.toboe512.maillis.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class CryptoServiceImplTest {

    private Context context;
    private CryptoServiceImpl aliceCryptoService;
    private CryptoServiceImpl bobCryptoService;

    @Before
    public void setUp() throws GeneralSecurityException, IOException {
        // Получаем контекст тестируемого приложения Android
        context = ApplicationProvider.getApplicationContext();

        // Инициализируем два независимых инстанса (как будто это два разных телефона)
        // Внимание: на одном эмуляторе они будут делить одни SharedPreferences,
        // но для тестирования сквозного кодирования это отлично подходит.

        aliceCryptoService = new CryptoServiceImpl(context);
        bobCryptoService = new CryptoServiceImpl(context);

        // Избавляемся от ошибки "Method d in android.util.Log not mocked"
        // Этот код перехватывает вызовы к Android Log и не дает тестам упасть
//        MockedStatic<Log> mockedLog = Mockito.mockStatic(Log.class);
//        mockedLog.when(() -> Log.d(Mockito.anyString(), Mockito.anyString())).thenReturn(0);
//        mockedLog.when(() -> Log.e(Mockito.anyString(), Mockito.anyString(), Mockito.any(Throwable.class))).thenReturn(0);

    }

    @Test
    public void testGetMyPublicKeyBytes_ShouldReturnValidBytes() {
        try {
            byte[] alicePublicKeyBytes = aliceCryptoService.getMyPublicKeyBytes();

            assertNotNull("Публичный ключ Алисы не должен быть null", alicePublicKeyBytes);
            assertTrue("Массив байт публичного ключа не должен быть пустым", alicePublicKeyBytes.length > 0);
        } catch (Exception e) {
            fail("Метод получения байт ключа выбросил исключение: " + e.getMessage());
        }
    }

    @Test
    public void testGetMyPublicKeyBase64_ShouldReturnValidString() {
        try {
            String alicePublicKeyBase64 = aliceCryptoService.getMyPublicKeyBas64();

            assertNotNull("Базовое Base64 представление ключа не должно быть null", alicePublicKeyBase64);
            assertTrue("Строка Base64 не должна быть пустой", alicePublicKeyBase64.trim().length() > 0);
        } catch (Exception e) {
            fail("Метод получения Base64 ключа выбросил исключение: " + e.getMessage());
        }
    }

    @Test
    public void testEndToEndEncryption_AliceToBob_ShouldSuccessfullyDecrypt() {
        String originalMessage = "Привет, Боб! Это секретное сквозное P2P сообщение.";

        try {
            // 1. Боб "передает" Алисе свой публичный ключ в виде массива байт
            byte[] bobsPublicKeyBytes = bobCryptoService.getMyPublicKeyBytes();
            assertNotNull(bobsPublicKeyBytes);

            // 2. Алиса шифрует исходный текст, используя публичный ключ Боба
            String encryptedBase64Text = aliceCryptoService.encrypt(originalMessage, bobsPublicKeyBytes);
            assertNotNull(encryptedBase64Text);

            // Проверяем, что текст зашифровался и больше не равен оригиналу
            assertTrue("Шифротекст должен отличаться от исходного сообщения", !originalMessage.equals(encryptedBase64Text));

            // 3. Боб получает зашифрованную Base64 строку и расшифровывает её СВОИМ приватным ключом
            String decryptedMessage = bobCryptoService.decrypted(encryptedBase64Text);

            // 4. ПРОВЕРКА: Текст после дешифрования обязан совпадать с оригиналом байт-в-байт
            assertEquals("Расшифрованное сообщение должно совпадать с исходным", originalMessage, decryptedMessage);

        } catch (Exception e) {
            fail("Сквозной тест шифрования упал с ошибкой: " + e.getMessage());
        }
    }

    @Test
    public void testEncrypt_WithNullOrEmptyPublicKey_ShouldThrowException() {
        String message = "Тестовое сообщение";

        // Проверка на передачу null
        try {
            aliceCryptoService.encrypt(message, null);
            fail("Метод encode должен был выбросить GeneralSecurityException при передачи null ключа");
        } catch (GeneralSecurityException e) {
            assertEquals("Публичный ключ собеседника пуст или не получен!", e.getMessage());
        } catch (IOException e) {
            fail("Ожидалось GeneralSecurityException, но выброшено IOException");
        }

        // Проверка на передачу пустого массива байт
        try {
            aliceCryptoService.encrypt(message, new byte[0]);
            fail("Метод encode должен был выбросить GeneralSecurityException при передаче пустого массива ключа");
        } catch (GeneralSecurityException e) {
            assertEquals("Публичный ключ собеседника пуст или не получен!", e.getMessage());
        } catch (IOException e) {
            fail("Ожидалось GeneralSecurityException, но выброшено IOException");
        }
    }

    @Test
    public void testDecode_Str_WithCorruptedCiphertext_ShouldThrowException() {
        // Передаем заведомо испорченную, но валидную для Base64 строку
        String corruptedBase64 = "YmFkX2NpcGhlcnRleHQ=";

        try {
            bobCryptoService.decrypted(corruptedBase64);
            fail("Метод decode должен выбросить GeneralSecurityException при попытке расшифровать битые данные");
        } catch (GeneralSecurityException e) {
            // Успех — Google Tink обнаружил подмену/порчу данных и выбросил decryption failed
            assertTrue(e.getMessage() == null || e.getMessage().contains("decryption failed"));
        } catch (IOException e) {
            fail("Ожидалось криптографическое исключение, но выброшено системное IOException");
        }
    }


}