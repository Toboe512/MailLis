package ru.toboe512.maillis.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class CryptoServiceImplStaticTest {
    // Тестовая строка, содержащая кириллицу, эмодзи и спецсимволы
    private static final String TEST_MESSAGE = "Привет, MailLis! 🔐 Проверка P2P групп 123456! @#$";

    @Test
    public void testGenerateGroupSymmetricKey_LengthIsCorrect() {
        byte[] key = CryptoServiceImpl.generateGroupSymmetricKey();

        assertNotNull("Ключ не должен быть null", key);
        assertEquals("Длина AES-256 ключа должна быть ровно 32 байта", 32, key.length);
    }

    @Test
    public void testGenerateGroupSymmetricKey_KeysAreRandomAndUnique() {
        byte[] key1 = CryptoServiceImpl.generateGroupSymmetricKey();
        byte[] key2 = CryptoServiceImpl.generateGroupSymmetricKey();

        assertFalse("Два сгенерированных ключа не должны совпадать", Arrays.equals(key1, key2));
    }

    @Test
    public void testEncryptAndDecryptGroupText_Success() throws Exception {
        byte[] groupKey = CryptoServiceImpl.generateGroupSymmetricKey();

        // Шифруем текстовые данные
        byte[] encryptedData = CryptoServiceImpl.encryptGroupText(TEST_MESSAGE, groupKey);
        assertNotNull("Зашифрованный массив не должен быть null", encryptedData);
        assertTrue("Размер зашифрованных данных слишком мал", encryptedData.length > 28);

        // Расшифровываем обратно
        String decryptedMessage = CryptoServiceImpl.decryptGroupText(encryptedData, groupKey);

        assertEquals("Расшифрованное сообщение не совпадает с исходным!", TEST_MESSAGE, decryptedMessage);
    }

    @Test
    public void testEncryptGroupText_UsesUniqueIVEachTime() throws Exception {
        byte[] groupKey = CryptoServiceImpl.generateGroupSymmetricKey();

        byte[] encrypted1 = CryptoServiceImpl.encryptGroupText(TEST_MESSAGE, groupKey);
        byte[] encrypted2 = CryptoServiceImpl.encryptGroupText(TEST_MESSAGE, groupKey);

        byte[] iv1 = Arrays.copyOfRange(encrypted1, 0, 12);
        byte[] iv2 = Arrays.copyOfRange(encrypted2, 0, 12);

        // Проверяем уникальность векторов инициализации (IV) для защиты от криптоанализа
        assertFalse("Векторы инициализации (IV) не должны повторяться!", Arrays.equals(iv1, iv2));
        assertFalse("Шифротексты одного сообщения должны отличаться", Arrays.equals(encrypted1, encrypted2));
    }

    @Test
    public void testDecryptGroupText_WithNullArguments_ReturnsEmptyString() throws Exception {
        byte[] validKey = CryptoServiceImpl.generateGroupSymmetricKey();

        // В вашем исходном методе объявлена константа EMPTI_STRING.
        // Если она равна "", то проверка пройдет успешно.
        assertEquals("", CryptoServiceImpl.decryptGroupText(null, validKey));
    }

    @Test(expected = Exception.class)
    public void testDecryptGroupText_WithWrongKey_ThrowsException() throws Exception {
        byte[] realKey = CryptoServiceImpl.generateGroupSymmetricKey();
        byte[] wrongKey = CryptoServiceImpl.generateGroupSymmetricKey();

        byte[] encryptedData = CryptoServiceImpl.encryptGroupText(TEST_MESSAGE, realKey);

        // Ожидаем ошибку AEADBadTagException (Exception), так как ключ не совпадает
        CryptoServiceImpl.decryptGroupText(encryptedData, wrongKey);
    }

    @Test(expected = Exception.class)
    public void testDecryptGroupText_WithCorruptedCiphertext_ThrowsException() throws Exception {
        byte[] groupKey = CryptoServiceImpl.generateGroupSymmetricKey();
        byte[] encryptedData = CryptoServiceImpl.encryptGroupText(TEST_MESSAGE, groupKey);

        // Имитируем умышленное искажение бинарного потока при передаче по SMTP: инвертируем бит
        encryptedData[encryptedData.length - 1] ^= 1;

        // Ожидаем исключение из-за нарушения целостности тега подлинности AES-GCM
        CryptoServiceImpl.decryptGroupText(encryptedData, groupKey);
    }
}
