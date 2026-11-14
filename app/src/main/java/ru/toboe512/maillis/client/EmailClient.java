package ru.toboe512.maillis.client;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.List;

import jakarta.mail.MessagingException;
import ru.toboe512.maillis.dto.IncomingMessageDto;

public interface EmailClient {

    List<IncomingMessageDto> fetchIncomingOverNetwork(SharedPreferences prefs) throws MessagingException, IOException;

    String sendEmailOverNetwork(String recipientEmail, String subject, final String messageText, SharedPreferences prefs) throws MessagingException;

    void deleteMessageFromServer(final String serverUid, SharedPreferences prefs);

    String sendImageOverNetwork(String recipientEmail, String base64EncryptedImage, SharedPreferences prefs) throws MessagingException;

    String sendGroupImageOverNetwork(List<String> recipients, String subject, String header, String base64EncryptedImage, SharedPreferences prefs) throws MessagingException;

    /**
     * Безопасно рассылает секретный AES-ключ группы участнику.
     * Ключ шифруется индивидуальным публичным Tink-ключом получателя.
     */
    String sendGroupMessageOverNetwork(List<String> recipients, String subject, String cipherTextBase64, SharedPreferences prefs) throws MessagingException;

    void sendGroupPreInviteResponse(String creatorEmail, long chatId, String myPublicKeyBas64, SharedPreferences prefs) throws MessagingException;

    /**
     * Безопасно рассылает секретный AES-ключ группы участнику.
     * Ключ шифруется индивидуальным публичным Tink-ключом получателя.
     */
    void sendGroupInviteOverNetwork(String recipientEmail, String subject, String header, String text, SharedPreferences prefs) throws MessagingException;

    /**
     * Отправляет служебную команду старым участникам группы для синхронизации состава.
     */
    void sendGroupSyncNotification(String oldMemberEmail, long groupId, String newMemberEmail, SharedPreferences prefs) throws Exception;

    /**
     * Кодирует локальный файл в Base64, шифрует его крипто-ядром Tink
     * и отправляет по P2P-сети MailLis в виде защищенного MIME-конверта [source].
     */
    void sendFileOverNetwork(String recipientEmail, String subject, String encryptedPayload, SharedPreferences prefs) throws MessagingException;
}
