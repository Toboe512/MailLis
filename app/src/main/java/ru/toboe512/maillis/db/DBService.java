package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.Message;

public interface DBService {
    List<Message> loadMessagesFromDatabase(int chatId, String myEmail);

    int saveMessageToDatabase(int chatId, String myEmail, String text, long timestamp) ;

    ChatDbHelper getDbHelper();
}
