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
    //String sendHandshakeResponseAsin(String targetEmail, long chatId, final int localMessageId, SharedPreferences prefs, CryptoService cryptoService);

    void deleteMessageFromMailRuServer(final String serverUid, Context context);

    void retryUnsentMessages(SharedPreferences prefs);

    String sendEmailOverNetworkSync(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs);
}
