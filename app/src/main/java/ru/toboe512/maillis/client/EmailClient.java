package ru.toboe512.maillis.client;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

import ru.toboe512.maillis.dto.Message;

public interface EmailClient {

    List<Message> fetchIncomingEmailsOverNetwork(long chatId, String myEmail, Context context, SharedPreferences prefs);

    List<Message> fetchIncomingHandshakeOverNetwork(Context context, SharedPreferences prefs);

    void sendEmailOverNetwork(long chatId, String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, EmailClientImpl.OnMessagesSynchronizedListener listener);

    byte[] sendHandshakeRequest(String targetEmail, Context context, long chatId, SharedPreferences prefs, String myPublicKey);

    void deleteMessageFromMailRuServer(final String serverUid, Context context);
}
