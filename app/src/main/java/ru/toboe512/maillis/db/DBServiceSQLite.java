package ru.toboe512.maillis.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public class DBServiceSQLite implements DBService {

    // Переменные для работы с базой данных
    private ChatDbHelper dbHelper;

    public DBServiceSQLite(Context context) {
        dbHelper = new ChatDbHelper(context);
    }

    // Метод чтения истории переписки из SQLite
    public List<Message> loadMessagesFromDatabase(int chatId, String myEmail) {
        List<Message> messageList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос истории сообщений строго для этого конкретного chatId
        Cursor cursor = db.query("messages", null, "chat_id = ?",
                new String[]{String.valueOf(chatId)}, null, null, "timestamp ASC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                int idIndex = cursor.getColumnIndex("id");
                // ИСПРАВЛЕНИЕ: Считываем реальный ID сообщения из базы данных!
                int realId = cursor.getInt(idIndex);
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");

                if (senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    String serverUid = cursor.getString(uidIndex);

                    // Если отправитель — "my@mail.ru", значит сообщение наше (флаг isMine = true)
                    // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Сравниваем с нашим настоящим Email из настроек
                    boolean isMine = myEmail.equalsIgnoreCase(sender != null ? sender.trim() : "");

                    messageList.add(new Message(realId, sender, text, time, isMine, serverUid));
                }
            }
            cursor.close();
        }
        db.close();

        return messageList;
    }

    // Метод сохранения сообщения в локальную таблицу SQLite
    public int saveMessageToDatabase(int chatId, String myEmail, String text, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put("chat_id", chatId);
        values.put("sender_email", myEmail); // Наш адрес
        values.put("message_text", text);
        values.put("timestamp", timestamp);
        long newId = -1;
        newId = db.insert("messages", null, values); // Получаем ID новой строки

        // Попутно обновляем последнее сообщение в таблице чатов для главного экрана
        ContentValues chatValues = new ContentValues();
        chatValues.put("last_message", text);
        chatValues.put("timestamp", timestamp);
        db.update("chats", chatValues, "id = ?", new String[]{String.valueOf(chatId)});

        db.close();

        return (int) newId;
    }

    // Метод чтения активных чатов из SQLite
    public List<ChatPreview> loadChatsFromDatabase() {
        List<ChatPreview> chatList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Берем все чаты из таблицы chats
        Cursor cursor = db.query("chats", null, null, null, null, null, "timestamp DESC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                int idIndex = cursor.getColumnIndex("id");
                int titleIndex = cursor.getColumnIndex("title");
                int typeIndex = cursor.getColumnIndex("type");
                int lastMsgIndex = cursor.getColumnIndex("last_message");

                if (idIndex >= 0 && titleIndex >= 0 && typeIndex >= 0 && lastMsgIndex >= 0) {
                    int id = cursor.getInt(idIndex);
                    String title = cursor.getString(titleIndex);
                    String type = cursor.getString(typeIndex);
                    String lastMessage = cursor.getString(lastMsgIndex);

                    chatList.add(new ChatPreview(id, title, type, lastMessage));
                }
            }
            cursor.close();
        }
        db.close();

        return chatList;
    }

    // Логика записи нового чата в SQLite
    public long saveChatToDatabase(String title, String targetEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put("title", title); // Красивое человеческое название (например, "Чат с Алексеем")
        values.put("type", "PERSONAL");
        values.put("recipient_email", targetEmail); // Сохраняем email в отдельную колонку!
        values.put("last_message", "Чат создан");
        values.put("timestamp", System.currentTimeMillis());

        long newChatId = db.insert("chats", null, values);
        db.close();
        return newChatId;
    }

    // Метод нативного чтения строк из таблицы SQLite
    public List<Contact> loadContactsFromDatabase() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос всех колонок из таблицы contacts
        Cursor cursor = db.query("contacts", null, null, null, null, null, "name ASC");
        List<Contact> contactList = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                // Получаем индексы колонок по их именам
                int idIndex = cursor.getColumnIndex("id");
                int nameIndex = cursor.getColumnIndex("name");
                int emailIndex = cursor.getColumnIndex("email");

                // Считываем значения типов данных
                if (idIndex >= 0 && nameIndex >= 0 && emailIndex >= 0) {
                    int id = cursor.getInt(idIndex);
                    String name = cursor.getString(nameIndex);
                    String email = cursor.getString(emailIndex);

                    // Добавляем объект в наш массив списка
                    contactList.add(new Contact(id, name, email));
                }
            }
            cursor.close(); // Обязательно закрываем курсор
        }
        db.close();

        return contactList;
    }

    // Нативное удаление строки из SQLite по ее ID
    public int deleteContactFromDb(int contactId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Запрос удаления: DELETE FROM contacts WHERE id = contactId
        int rowsDeleted = db.delete("contacts", "id = ?", new String[]{String.valueOf(contactId)});
        db.close();

        return rowsDeleted;
    }

    public ChatDbHelper getDbHelper() {
        return dbHelper;
    }
}
