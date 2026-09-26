package ru.toboe512.maillis.client;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.dto.Message;

public interface EmailClient {

    CryptoService getCryptoService();

    //List<Message> fetchIncomingEmailsOverNetwork(long chatId, String myEmail, Context context, SharedPreferences prefs);

    List<Message> fetchIncomingHandshakeOverNetwork(Context context, SharedPreferences prefs);

    boolean sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, EmailClientImpl.OnMessagesSynchronizedListener listener);

    boolean sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs);

    String sendHandshakeResponse(String targetEmail, long chatId, SharedPreferences prefs);
    String sendHandshakeResponseAsin(String targetEmail, long chatId, final int localMessageId, SharedPreferences prefs, CryptoService cryptoService);

    void deleteMessageFromMailRuServer(final String serverUid, Context context);

    void retryUnsentMessages(SharedPreferences prefs);
}
