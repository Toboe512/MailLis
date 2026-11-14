package ru.toboe512.maillis.service;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public interface MailExecutorService {

    int fetchIncomingOverNetwork(Context context, SharedPreferences prefs);

    boolean sendEmailOverNetwork(String recipientEmail, String subject, final String messageText, final int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener);

    boolean sendHandshakeRequest(String targetEmail, long chatId, SharedPreferences prefs);

    String sendHandshakeResponse(String targetEmail, long chatId, SharedPreferences prefs);

    void retryUnsentMessages(SharedPreferences prefs);

    void sendImageOverNetwork(long chatId, String recipientEmail, byte[] rawImageBytes,
                              final int localMessageId, SharedPreferences prefs, MailExecutorServiceImpl.OnMessagesSynchronizedListener listener);

    void sendGroupImageOverNetwork(long chatId, byte[] rawImageBytes, int localMessageId, SharedPreferences prefs);

    void sendGroupMessageOverNetwork(long chatId, String subject, String cipherTextBase64, int localMessageId, SharedPreferences prefs);

    void sendGroupInviteOverNetwork(String recipientEmail, long chatId, String groupName, byte[] groupSecretKey, SharedPreferences prefs);

    void sendFileOverNetwork(String recipientEmail, String subject, long chatId, File fileToSend, boolean isGroup, SharedPreferences prefs);

}
