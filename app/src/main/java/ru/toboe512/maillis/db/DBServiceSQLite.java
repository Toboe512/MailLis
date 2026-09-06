package ru.toboe512.maillis.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.dto.Message;

public class DBServiceSQLite  implements DBService {

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

    public ChatDbHelper getDbHelper() {
        return dbHelper;
    }
}
