package ru.toboe512.maillis.db;

import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import jakarta.mail.internet.InternetAddress;
import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public class DBServiceSQLite implements DBService {

    // Переменные для работы с базой данных
    private final ChatDbHelper dbHelper;

    public DBServiceSQLite(ChatDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    // Метод чтения истории переписки из SQLite
    public List<Message> getMessages(long chatId, String myEmail) {
        List<Message> messageList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Используем try-with-resources для автоматического закрытия Курсора в любых ситуациях
        try (Cursor cursor = db.query("messages", null, "chat_id = ?",
                new String[]{String.valueOf(chatId)}, null, null, "timestamp ASC")) {

            if (cursor.moveToFirst()) {
                // 1. ВЫНОСИМ НАВЕРХ: Узнаем индексы колонок строго ОДИН РАЗ до начала цикла
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int subjectIndex = cursor.getColumnIndex("subject");

                // 2. Безопасная проверка: все ли критически важные колонки существуют в БД
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                    do {
                        // Читаем данные строки
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);

                        // Необязательные колонки (могут не проверяться в if, но читаются безопасно)
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : "";
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : "";

                        // Проверяем, наше ли это сообщение
                        boolean isMine = myEmail.equalsIgnoreCase(sender != null ? sender.trim() : "");

                        // Добавляем готовый объект в список
                        messageList.add(new Message(realId, sender, subject, text, time, isMine, serverUid));

                    } while (cursor.moveToNext()); // Переходим к следующей строке переписки
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // Логируем ошибку, если она возникнет
        } finally {
            // Гарантированно закрываем подключение к БД
            if (db != null && db.isOpen()) {
                db.close();
            }
        }

        return messageList;
    }

    public Message getLastMessages(long chatId) {
        List<Message> messageList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос истории сообщений строго для этого конкретного chatId
        Cursor cursor = db.query("messages", null, "chat_id = ?",
                new String[]{String.valueOf(chatId)}, null, null, "timestamp ASC");

        while (cursor.moveToNext()) {
            int idIndex = cursor.getColumnIndex("id");
            // ИСПРАВЛЕНИЕ: Считываем реальный ID сообщения из базы данных!
            int realId = cursor.getInt(idIndex);
            int senderIndex = cursor.getColumnIndex("sender_email");
            int textIndex = cursor.getColumnIndex("message_text");
            int timeIndex = cursor.getColumnIndex("timestamp");
            int uidIndex = cursor.getColumnIndex("server_uid");
            int subjectIndex = cursor.getColumnIndex("subject");
            int senderEmailIndex = cursor.getColumnIndex("sender_email");

            if (senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                String sender = cursor.getString(senderIndex);
                String subject = cursor.getString(subjectIndex);
                String text = cursor.getString(textIndex);
                long time = cursor.getLong(timeIndex);
                String serverUid = cursor.getString(uidIndex);
                String senderEmail = cursor.getString(senderEmailIndex);

                // Если отправитель — "my@mail.ru", значит сообщение наше (флаг isMine = true)
                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Сравниваем с нашим настоящим Email из настроек
                boolean isMine = senderEmail.equalsIgnoreCase(sender != null ? sender.trim() : "");

                messageList.add(new Message(realId, sender, subject, text, time, isMine, serverUid));
            }
        }
        cursor.close();
        db.close();

        return messageList.isEmpty() ? new Message(0, "", "", "", 0L, false, "") : messageList.get(0);
    }

    // Метод сохранения сообщения в локальную таблицу SQLite
    public int saveMessage(long chatId, String myEmail, String subject, String text, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newId = -1;

        try {
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", myEmail);
            values.put("message_text", text); // В саму историю сообщений пишем чистый расшифрованный текст
            values.put("subject", subject);
            values.put("timestamp", timestamp);

            newId = db.insert("messages", null, values);

            // Формируем красивый текст для превью на главном экране
            String previewText = text;
            if (subject != null) {
                if (subject.contains("[MailLis-Key-Request]")) {
                    previewText = "🔑 Запрос безопасного соединения...";
                } else if (subject.contains("[MailLis-Key-Response]")) {
                    previewText = "🤝 Безопасное соединение установлено";
                }
            }

            // Попутно обновляем последнее сообщение в таблице чатов для главного экрана
            ContentValues chatValues = new ContentValues();
            chatValues.put("last_message", previewText); // Пишем красивый текст, а не Base64!
            chatValues.put("timestamp", timestamp);

            db.update("chats", chatValues, "id = ?", new String[]{String.valueOf(chatId)});

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Гарантированно закрываем базу данных, даже если произошел сбой
            if (db != null && db.isOpen()) {
                db.close();
            }
        }

        return (int) newId;
    }

    // Метод чтения активных чатов из SQLite
    public List<ChatPreview> loadChats() {
        List<ChatPreview> chatList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Используем try-with-resources для гарантированного закрытия Курсора
        try (Cursor cursor = db.query("chats", null, null, null, null, null, "timestamp DESC")) {

            if (cursor.moveToFirst()) {
                // 1. Узнаем индексы колонок ОДИН РАЗ до начала цикла (это экономит ресурсы процессора)
                int idIndex = cursor.getColumnIndex("id");
                int titleIndex = cursor.getColumnIndex("title");
                int typeIndex = cursor.getColumnIndex("type");
                int lastMsgIndex = cursor.getColumnIndex("last_message");

                // Проверяем, что все колонки существуют
                if (idIndex >= 0 && titleIndex >= 0 && typeIndex >= 0 && lastMsgIndex >= 0) {
                    do {
                        int id = cursor.getInt(idIndex);
                        String title = cursor.getString(titleIndex);
                        String type = cursor.getString(typeIndex);
                        String lastMessage = cursor.getString(lastMsgIndex);

                        // 2. БЕЗОПАСНАЯ ПРОВЕРКА НА РУКОПОЖАТИЕ
                        boolean isHandShake = false;
                        try {
                            var lastMsg = getLastMessages(id); // Получаем последнее сообщение чата

                            // Проверяем на null: если сообщений нет, то это не handshake, а просто пустой чат
                            if (lastMsg != null && lastMsg.getSubject() != null) {
                                isHandShake = !lastMsg.getSubject().contains(MAILLIS_CHAT_MESSAGE);
                            }
                        } catch (Exception e) {
                            e.printStackTrace(); // Защищаем цикл от падения, если в getLastMessages ошибка
                        }

                        // Добавляем чат в список
                        chatList.add(new ChatPreview(id, title, type, lastMessage, isHandShake));

                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Гарантированно закрываем базу данных в любом случае
            if (db != null && db.isOpen()) {
                db.close();
            }
        }

        return chatList;
    }

    // Логика записи нового чата в SQLite
    public long saveChat(String title, String targetEmail) {
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

    public boolean saveSharedSecretKey(String email, byte[] sharedSecretKeyBytes) {
        if (email == null || sharedSecretKeyBytes == null) return false;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsUpdated = 0;

        try {
            String cleanEmail = email.trim();

            ContentValues values = new ContentValues();
            values.put("shared_secret", sharedSecretKeyBytes); // Записываем байты напрямую в BLOB колонку

            // 1. Сначала пробуем обновить существующий чат
            rowsUpdated = db.update("chats", values, "recipient_email = ?", new String[]{cleanEmail});

            // 2. ИСПРАВЛЕНИЕ: Если rowsUpdated == 0, значит чата с этим человеком еще не было!
            if (rowsUpdated == 0) {
                // Создаем новую запись чата, чтобы ключ не потерялся
                values.put("recipient_email", cleanEmail);
                values.put("title", cleanEmail); // В качестве имени чата временно ставим его email
                values.put("type", "PERSONAL");
                values.put("last_message", "Чат инициализирован (ключ получен)");
                values.put("timestamp", System.currentTimeMillis());

                long insertedId = db.insert("chats", null, values);

                // Если вставка прошла успешно, возвращаем true
                return insertedId != -1;
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Обязательно закрываем базу данных в блоке finally
            if (db != null && db.isOpen()) {
                db.close();
            }
        }

        // Если обновилась хотя бы одна строка, вернет true
        return rowsUpdated > 0;
    }

    // Метод нативного чтения строк из таблицы SQLite
    public List<Contact> getContacts() {
        List<Contact> contactList = new ArrayList<>();
        try (SQLiteDatabase db = dbHelper.getReadableDatabase()) {

            // Запрос всех колонок из таблицы contacts
            Cursor cursor = db.query("contacts", null, null, null, null, null, "name ASC");

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
        } catch (Exception e) {
            e.printStackTrace();
        }

        return contactList;
    }

    // Нативное удаление строки из SQLite по ее ID
    public int deleteContact(int contactId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Запрос удаления: DELETE FROM contacts WHERE id = contactId
        int rowsDeleted = db.delete("contacts", "id = ?", new String[]{String.valueOf(contactId)});
        db.close();

        return rowsDeleted;
    }
    public int deleteContact(String email) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Запрос удаления: DELETE FROM contacts WHERE email = email
        int rowsDeleted = db.delete("contacts", "email = ?", new String[]{String.valueOf(email)});
        db.close();

        return rowsDeleted;
    }

    public ChatDbHelper getDbHelper() {
        return dbHelper;
    }

    @Override
    public long getChatIdByRecipientEmail(String email) {
        if (email == null) return -1;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        long id = -1;

        // Используем try-with-resources для автоматического закрытия Курсора
        try (Cursor cursor = db.query("chats", new String[]{"id"}, "recipient_email = ?",
                new String[]{email.trim()}, null, null, null)) {

            // Проверяем, нашла ли база данных хотя бы одну строку
            if (cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex("id");
                if (idIndex >= 0) {
                    // Извлекаем САМО ЗНАЧЕНИЕ (id чата), а не индекс колонки
                    id = cursor.getLong(idIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.close(); // Обязательно закрываем базу данных
        }

        return id;
    }

    @Override
    public int deleteMassage(int msdId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsDeleted = db.delete("messages", "id = ?", new String[]{String.valueOf(msdId)});
        db.close();
        return rowsDeleted;
    }

    @Override
    public boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid) {
        boolean hasNewData = false;
        try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
            Cursor c = db.rawQuery("SELECT id FROM messages WHERE server_uid = ?", new String[]{serverUid});
            if (c.getCount() == 0) {
                ContentValues values = new ContentValues();
                values.put("chat_id", chatId);
                values.put("sender_email", senderEmail);
                values.put("subject", subject);
                values.put("message_text", messageText);
                values.put("timestamp", currentTimestamp);
                values.put("server_uid", serverUid);
                db.insert("messages", null, values);

                hasNewData = true;
            }
            c.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return hasNewData;
    }

    @Override
    public void updateServerUid(String generatedServerUid, int localMessageId) {
        if (generatedServerUid != null) {
            try (SQLiteDatabase db = dbHelper.getWritableDatabase();) {
                ContentValues values = new ContentValues();
                values.put("server_uid", generatedServerUid);
                db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String getRecipientEmailByChatId(long chatId) {

        String recipientEmail = "";
        // БЕЗОПАСНЫЙ И ТОЧНЫЙ ПОИСК ПОЛУЧАТЕЛЯ ИЗ ТАБЛИЦЫ ЧАТОВ
        // Используем try-with-resources — Java-аналог метода .use из Kotlin
        try (SQLiteDatabase db = dbHelper.getReadableDatabase();
             Cursor cursor = db.query(
                     "chats",
                     new String[]{"recipient_email"},
                     "id = ?",
                     new String[]{String.valueOf(chatId)},
                     null, null, null
             )) {

            if (cursor.moveToFirst()) {
                int emailIdx = cursor.getColumnIndex("recipient_email");
                if (emailIdx >= 0) {
                    recipientEmail = cursor.getString(emailIdx);
                }
            }
        } catch (Exception e) {
            //TODO добавить проброс ошибки и логирование
            e.printStackTrace();
        }
        return recipientEmail;
    }

    @Override
    public byte[] getSharedSecret(long chatId) {
        byte[] publicKey = null;
        try (SQLiteDatabase db = dbHelper.getReadableDatabase();
             Cursor cursor = db.query(
                     "chats",
                     new String[]{"shared_secret"},
                     "id = ?",
                     new String[]{String.valueOf(chatId)},
                     null, null, null)) {

            // Так как ID уникален, проверяем строго ОДНУ первую запись
            if (cursor.moveToFirst()) {
                // Узнаем индекс колонки один раз
                int publicKeyIndex = cursor.getColumnIndex("shared_secret");

                if (publicKeyIndex >= 0) {
                    // Считываем массив байт (BLOB)
                    publicKey = cursor.getBlob(publicKeyIndex);
                }
            }

        } catch (Exception e) {
            e.printStackTrace(); // Логируем ошибку, если она возникнет
        }
        return publicKey;
    }
}
