package ru.toboe512.maillis.client;

import android.content.Context;
import android.content.SharedPreferences;

import ru.toboe512.maillis.crypto.CryptoService;

public interface EmailClient {

    CryptoService getCryptoService();

    boolean fetchIncomingOverNetwork(Context context, SharedPreferences prefs);

    boolean sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, EmailClientImpl.OnMessagesSynchronizedListener listener);

    boolean sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs);

    String sendHandshakeResponse(String targetEmail, long chatId, SharedPreferences prefs);

    void deleteMessageFromServer(final String serverUid, android.content.SharedPreferences prefs);

    void retryUnsentMessages(SharedPreferences prefs);

    String sendEmailOverNetworkSync(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs);

    void sendImageOverNetwork(long chatId, String recipientEmail, byte[] rawImageBytes, final int localMessageId, SharedPreferences prefs, EmailClientImpl.OnMessagesSynchronizedListener listener);

    void sendGroupImageOverNetwork(long chatId, byte[] rawImageBytes, int localMessageId, SharedPreferences prefs);
    /**
     * Безопасно рассылает секретный AES-ключ группы участнику.
     * Ключ шифруется индивидуальным публичным Tink-ключом получателя.
     */
    void sendGroupMessageOverNetwork(long chatId, String subject, String cipherTextBase64, int localMessageId, SharedPreferences prefs);

    void sendGroupPreInviteResponse(String creatorEmail, long chatId, SharedPreferences prefs);

    /**
     * Безопасно рассылает секретный AES-ключ группы участнику.
     * Ключ шифруется индивидуальным публичным Tink-ключом получателя.
     */
    void sendGroupInviteOverNetwork(String recipientEmail, long chatId, String groupName, byte[] groupSecretKey, SharedPreferences prefs);

    /**
     * Отправляет служебную команду старым участникам группы для синхронизации состава.
     */
    void sendGroupSyncNotification(String oldMemberEmail, long groupId, String newMemberEmail, SharedPreferences prefs) throws Exception;
}
