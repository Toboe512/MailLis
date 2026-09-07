package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public interface DBService {
    List<Message> loadMessagesFromDatabase(int chatId, String myEmail);

    int saveMessageToDatabase(int chatId, String myEmail, String text, long timestamp) ;

    List<ChatPreview> loadChatsFromDatabase();

    long saveChatToDatabase(String title, String targetEmail);

    List<Contact> loadContactsFromDatabase();

    int deleteContactFromDb(int contactId);
    ChatDbHelper getDbHelper();
}
