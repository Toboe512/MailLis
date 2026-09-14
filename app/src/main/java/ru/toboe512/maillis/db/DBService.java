package ru.toboe512.maillis.db;

import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public interface DBService {
    List<Message> getMessages(long chatId, String myEmail);

    int saveMessage(int chatId, String myEmail, String subject, String text, long timestamp) ;

    List<ChatPreview> loadChats();

    long saveChat(String title, String targetEmail);

    boolean savePeerPublicKey(String email, byte[] publicKeyBytes);

    List<Contact> getContacts();

    int deleteContactFromDb(int contactId);
    ChatDbHelper getDbHelper();

    long getChatIdBySender(String sender);

    int deleteMassage(int msdId);


}
