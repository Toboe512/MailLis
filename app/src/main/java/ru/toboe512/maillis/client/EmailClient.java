package ru.toboe512.maillis.client;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.List;

import jakarta.mail.MessagingException;
import ru.toboe512.maillis.dto.NetworkMessageDto;

public interface EmailClient {

    List<NetworkMessageDto> fetch(SharedPreferences prefs) throws MessagingException, IOException;

    String sendMessage(NetworkMessageDto messageDto, SharedPreferences prefs) throws MessagingException;

    /**
     * Удаления письма по протоколу IMAP
     *
     * @param serverUid идентификатор письма
     * @param prefs     интерфейс для доступа к данным настроек
     */
    void deleteMessage(final String serverUid, SharedPreferences prefs);

    String sendGroupMessage(List<String> recipients, NetworkMessageDto messageDto, SharedPreferences prefs) throws MessagingException;
}
