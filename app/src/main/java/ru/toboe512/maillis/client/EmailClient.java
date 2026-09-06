package ru.toboe512.maillis.client;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

import ru.toboe512.maillis.dto.Message;

public interface EmailClient {

    List<Message>  fetchIncomingEmailsOverNetwork(int chatId, String myEmail, SharedPreferences prefs);
    List<Message> sendEmailOverNetwork(int chatId, String myEmail, String recipientEmail, final String messageText, final int localMessageId, SharedPreferences prefs);
}
