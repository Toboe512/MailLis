package ru.toboe512.maillis.db;

import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.Message;

public class DBServiceSQLite implements DBService {

    // Переменные для работы с базой данных
    private final ChatDbHelper dbHelper;


    // 2. Внутренний статический класс-держатель.
    // Он загрузится в память строго в тот момент, когда впервые вызовут метод getInstance()
    private static class Holder {
        private static DBServiceSQLite INSTANCE;
    }

    // 3. Единственная глобальная точка доступа (Потокобезопасно на уровне JVM)
    public static DBServiceSQLite getInstance(Context context) {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new DBServiceSQLite(context);
        }
        return Holder.INSTANCE;
    }

    private DBServiceSQLite(Context cotext) {
        this.dbHelper = new ChatDbHelper(cotext.getApplicationContext());
    }

    // Метод чтения истории переписки из SQLite
    public List<Message> getAllMessages(long chatId, String myEmail) {
        var messageList = new ArrayList<Message>();
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
                int isMineIndex = cursor.getColumnIndex("is_mine");
                int statusIndex = cursor.getColumnIndex("is_sent");

                // 2. Безопасная проверка: все ли критически важные колонки существуют в БД
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >=0) {
                    do {
                        // Читаем данные строки
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);
                        int status = cursor.getInt(statusIndex);

                        // Необязательные колонки (могут не проверяться в if, но читаются безопасно)
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                        // ОПТИМИЗАЦИЯ: Больше не сравниваем строки. Берем готовый бит из базы данных!
                        boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                        // Добавляем готовый объект в список
                        messageList.add(new Message(realId, sender, subject, text, time, isMine, serverUid, status));

                    } while (cursor.moveToNext()); // Переходим к следующей строке переписки
                }
            }
        }

        return messageList;
    }

    public List<Message> getMessages(long chatId, String myEmail) {
        return getAllMessages(chatId, myEmail)
                .stream()
                .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE))
                .collect(Collectors.toList());
    }

    /**
     * Моментально возвращает последнее сообщение чата.
     * Использует SQL-оптимизацию LIMIT 1, чтобы не фризить главный экран.
     */
    public Message getLastMessages(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Message lastMessage = null;

        // Запрашиваем строго ОДНУ строку (LIMIT 1), отсортированную от новых к старым (DESC)
        try (Cursor cursor = db.query(
                "messages",
                null,
                "chat_id = ?",
                new String[]{String.valueOf(chatId)},
                null, null,
                "timestamp DESC",
                "1" // Ультимативная оптимизация LIMIT 1
        )) {

            if (cursor.moveToFirst()) {
                // Узнаем индексы колонок строго ОДИН РАЗ до начала чтения
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int subjectIndex = cursor.getColumnIndex("subject");
                int isMineIndex = cursor.getColumnIndex("is_mine"); // Индекс новой колонки
                int statusIndex = cursor.getColumnIndex("is_sent");

                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);

                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Считываем готовое значение из БД: если в базе лежит 1, то это true, иначе false
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Собираем объект сообщения
                    lastMessage = new Message(realId, sender, subject, text, time, isMine, serverUid, status);
                }
            }
        }
        // Если чат пустой, возвращаем дефолтный объект во избежание NullPointerException на главном экране
        return lastMessage != null
                ? lastMessage :
                new Message(0, EMPTI_STRING, EMPTI_STRING, "Сообщений нет", 0L, false, EMPTI_STRING, 0);
    }

    // Метод сохранения сообщения в локальную таблицу SQLite
    // 1. Изменяем метод сохранения (ставим статус 0 при создании)
    public int saveMessage(long chatId, String myEmail, String subject, String text, boolean isMine, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newId = -1;

        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("sender_email", myEmail);
        values.put("message_text", text);
        values.put("subject", subject);

        values.put("is_mine", isMine ? 1 : 0);
        values.put("timestamp", timestamp);
        values.put("is_sent", 0); // 0 = Новое, еще не улетело в сеть

        newId = db.insert("messages", null, values);

        ContentValues chatValues = new ContentValues();
        chatValues.put("last_message", text);
        chatValues.put("timestamp", timestamp);
        db.update("chats", chatValues, "id = ?", new String[]{String.valueOf(chatId)});

        return (int) newId;
    }

    /**
     * Находит и возвращает конкретное сообщение из таблицы messages по его уникальному ID.
     *
     * @param localMessageId уникальный локальный идентификатор сообщения (id)
     * @return объект Message с заполненными данными или null, если сообщение не найдено
     */
    public Message getMessage(long localMessageId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Message message = null;

        // Ищем строго одну строку, где id совпадает с переданным localMessageId
        try (Cursor cursor = db.query(
                "messages",
                null,
                "id = ?",
                new String[]{String.valueOf(localMessageId)},
                null, null, null
        )) {

            // Проверяем, нашла ли база данных строку
            if (cursor != null && cursor.moveToFirst()) {
                // Узнаем индексы колонок строго ОДИН РАЗ (без циклов)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int subjectIndex = cursor.getColumnIndex("subject");
                int isMineIndex = cursor.getColumnIndex("is_mine"); // Считываем новое поле статуса
                int isSendIndex = cursor.getColumnIndex("is_sent");

                // Проверяем существование критически важных колонок
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int isSend =cursor.getInt(isSendIndex);

                    // Безопасное чтение необязательных текстовых колонок
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Упрощенное и безопасное считывание бита is_mine напрямую из базы
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Создаем объект сообщения
                    message = new Message(realId, sender, subject, text, time, isMine, serverUid, isSend);
                }
            }
        }

        return message;
    }

    // 2. ДОБАВЛЯЕМ МЕТОД: Пометка сообщения как успешно отправленного
    public void markMessageAsSent(int localMessageId, String serverUid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 1); // Успешно улетело!
        if (serverUid != null) {
            values.put("server_uid", serverUid);
        }
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
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

                        var lastMsg = getLastMessages(id); // Получаем последнее сообщение чата

                        var msg = EMPTI_STRING;
                        // Проверяем на null: если сообщений нет, то это не handshake, а просто пустой чат
                        if (lastMsg != null && lastMsg.getSubject() != null) {
                            isHandShake = !lastMsg.getSubject().contains(MAILLIS_CHAT_MESSAGE);
                            msg = isHandShake ? "Чат создан" : EMPTI_STRING;
                        }


                        // Добавляем чат в список
                        chatList.add(new ChatPreview(id, title, type, msg, isHandShake));

                    } while (cursor.moveToNext());
                }
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

        return db.insert("chats", null, values);
    }

    public boolean saveSharedSecretKey(String email, byte[] sharedSecretKeyBytes) {
        if (email == null || sharedSecretKeyBytes == null) {
            return false;
        }

        int rowsUpdated;
        SQLiteDatabase db = dbHelper.getWritableDatabase();

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

        // Если обновилась хотя бы одна строка, вернет true
        return rowsUpdated > 0;
    }

    // Метод нативного чтения строк из таблицы SQLite
    public List<Contact> getContacts() {
        List<Contact> contactList = new ArrayList<>();

        // НЕ закрываем саму базу данных (db.close()), так как это ломает пул соединений приложения,
        // но ОБЯЗАТЕЛЬНО оборачиваем Cursor в try-with-resources для автоматического закрытия!
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрос всех колонок из таблицы contacts
        try (// Вместо полной выборки делаем выборку с фильтрацией через inner/sub-запрос:
             Cursor cursor = db.rawQuery(
                     "SELECT * FROM contacts WHERE email NOT IN (SELECT email FROM ban_list) ORDER BY name ASC",
                     null
             )) {

            if (cursor.moveToFirst()) {
                // 1. ОПТИМИЗАЦИЯ: Узнаем индексы колонок строго ОДИН РАЗ до начала цикла
                int idIndex = cursor.getColumnIndex("id");
                int nameIndex = cursor.getColumnIndex("name");
                int emailIndex = cursor.getColumnIndex("email");

                // 2. Проверяем, что все необходимые колонки физически существуют в таблице
                if (idIndex >= 0 && nameIndex >= 0 && emailIndex >= 0) {
                    do {
                        // Считываем значения из текущей строки
                        int id = cursor.getInt(idIndex);
                        String name = cursor.getString(nameIndex);
                        String email = cursor.getString(emailIndex);

                        // Добавляем готовый объект в наш список
                        contactList.add(new Contact(id, name, email));

                    } while (cursor.moveToNext()); // Переходим к следующему контакту
                }
            }
        }
        return contactList;
    }


    // Нативное удаление строки из SQLite по ее ID
    public int deleteContact(int contactId) {
        // Запрос удаления: DELETE FROM contacts WHERE id = contactId
        return dbHelper.getWritableDatabase().delete("contacts", "id = ?", new String[]{String.valueOf(contactId)});
    }

    public int deleteContact(String email) {
        // Запрос удаления: DELETE FROM contacts WHERE email = email
        return dbHelper.getWritableDatabase().delete("contacts", "email = ?", new String[]{String.valueOf(email)});
    }


    /**
     * Находит и возвращает ID чата по Email-адресу собеседника.
     * Защищено от багов разного регистра букв (Case-Insensitive) в адресах Mail.ru.
     *
     * @param email почтовый адрес собеседника
     * @return уникальный ID чата из таблицы chats, либо -1, если чат еще не создан
     */
    @Override
    public long getChatIdByRecipientEmail(String email) {
        if (email == null || email.isEmpty()) {
            return -1;
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        long chatId = -1;

        // ИСПРАВЛЕНИЕ 1: Принудительно очищаем пробелы и переводим в нижний регистр для стандартизации
        String normalizedEmail = email.trim().toLowerCase();

        // ИСПРАВЛЕНИЕ 2: Используем оператор "LIKE" вместо "=" для безопасного регистронезависимого поиска
        try (Cursor cursor = db.query(
                "chats",
                new String[]{"id"},
                "recipient_email LIKE ?",
                new String[]{normalizedEmail},
                null, null, null)) {

            // Проверяем, нашла ли база данных хотя бы одну строку
            if (cursor != null && cursor.moveToFirst()) {
                // Выносим поиск индекса наверх (для максимальной производительности)
                int idIndex = cursor.getColumnIndex("id");
                if (idIndex >= 0) {
                    // Извлекаем реальное числовое значение ID чата из SQLite
                    chatId = cursor.getLong(idIndex);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при поиске chatId по email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    @Override
    public int deleteMessage(int msdId) {
        return dbHelper.getWritableDatabase().delete("messages", "id = ?", new String[]{String.valueOf(msdId)});
    }

    @Override
    public boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp, String serverUid) {
        boolean hasNewData = false;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT id FROM messages WHERE server_uid = ?", new String[]{serverUid})) {

            if (c.getCount() == 0) {
                ContentValues values = new ContentValues();
                values.put("chat_id", chatId);
                values.put("sender_email", senderEmail);
                values.put("subject", subject);
                values.put("message_text", messageText);
                values.put("timestamp", currentTimestamp);
                values.put("is_sent", 2);
                values.put("server_uid", serverUid);
                db.insert("messages", null, values);

                hasNewData = true;
            }
        }
        return hasNewData;
    }

    @Override
    public void updateServerUid(String generatedServerUid, int localMessageId) {
        if (generatedServerUid != null) {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            var values = new ContentValues();
            values.put("server_uid", generatedServerUid);
            db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
        }
    }

    @Override
    /**
     * Находит и возвращает Email получателя (собеседника) по уникальному ID чата.
     * Защищено от багов приведения типов (Type Mismatch) в современных Android SDK.
     *
     * @param chatId уникальный числовой идентификатор чата из таблицы chats
     * @return email адрес собеседника или пустая строка, если чат не найден
     */
    public String getRecipientEmailByChatId(long chatId) {
        String recipientEmail = EMPTI_STRING;
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Пишем чистый SQL-запрос. Но числовое значение chatId подставляем напрямую
        // через String.valueOf, но внутри rawQuery SQLite выполнит безопасный авто-кастинг.
        String sql = "SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";

        // Используем try-with-resources для автоматического закрытия Курсора в любых ситуациях
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {

            // Проверяем, нашла ли база данных строку
            if (cursor != null && cursor.moveToFirst()) {
                int emailIdx = cursor.getColumnIndex("recipient_email");
                if (emailIdx >= 0) {
                    // Безопасно извлекаем текстовый email
                    String result = cursor.getString(emailIdx);
                    if (result != null) {
                        recipientEmail = result.trim();
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при поиске recipient_email по chatId: " + chatId, e);
            e.printStackTrace();
        }

        return recipientEmail;
    }
    @Override
    public byte[] getSevedPublicKey(long chatId) {
        byte[] publicKey = null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(
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

        }
        return publicKey;
    }

    public List<Message> getUnsentMessages() {
        List<Message> unsentList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Пишем чистый SQL-запрос. Ноль передается как ЧИСЛО (is_sent = 0),
        // что на 100% исключает проблему, когда SQLite не может сравнить строку с числом.
        String sqlQuery = "SELECT * FROM messages WHERE is_sent = 0 ORDER BY timestamp ASC";

        try (Cursor cursor = db.rawQuery(sqlQuery, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                // Выносим поиск индексов строго наверх ДО начала итераций (для максимальной скорости)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int isMineIndex = cursor.getColumnIndex("is_mine");
                int statusIndex = cursor.getColumnIndex("is_sent");

                // Проверяем существование критически важных для структуры полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex>=0) {
                    do {
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);
                        int status = cursor.getInt(statusIndex);

                        // Безопасное чтение необязательных текстовых колонок
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                        // Упрощенное и безопасное считывание флага из базы
                        boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                        // Создаем чистый объект сообщения
                        Message message = new Message(realId, sender, subject, text, time, isMine, serverUid, status);
                        unsentList.add(message);

                    } while (cursor.moveToNext()); // Переходим к следующему неотправленному письму

                    Log.d("MailLis_DB", "getUnsentMessages() успешно нашёл в SQLite строк: " + unsentList.size());
                } else {
                    Log.e("MailLis_DB", "Критическая ошибка: В таблице messages не найдены базовые колонки!");
                }
            } else {
                Log.d("MailLis_DB", "getUnsentMessages(): Очередь неотправленных сообщений пуста (0 писем со статусом 0).");
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критический сбой выполнения rawQuery в getUnsentMessages()", e);
            e.printStackTrace();
        }

        return unsentList;
    }

    public int getChatIdByMessageId(int messageId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int chatId = -1;

        // Запрашиваем только колонку chat_id для строки с указанным id
        try (Cursor cursor = db.query(
                "messages",
                new String[]{"chat_id"},
                "id = ?",
                new String[]{String.valueOf(messageId)},
                null, null, null
        )) {

            // Так как id уникален, проверяем строго одну первую запись
            if (cursor.moveToFirst()) {
                int chatIdIndex = cursor.getColumnIndex("chat_id");
                if (chatIdIndex >= 0) {
                    chatId = cursor.getInt(chatIdIndex);
                }
            }
        }
        return chatId;
    }

    // 1. Добавить пользователя в бан-лист
    public boolean banUser(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long id = -1;
        try {
            ContentValues values = new ContentValues();
            values.put("email", email.trim().toLowerCase());
            values.put("timestamp", System.currentTimeMillis());

            // insertWithOnConflict защитит от дубликатов, если пользователя случайно забанят дважды
            id = db.insertWithOnConflict("ban_list", null, values, SQLiteDatabase.CONFLICT_IGNORE);

            // Опционально: при бане можно сразу пометить чат с ним как заблокированный или удалить его
        } catch (Exception e) {
            e.printStackTrace();
        }
        return id != -1;
    }

    // 2. Удалить пользователя из бан-листа (разбанить)
    public boolean unbanUser(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rows = 0;
        try {
            rows = db.delete("ban_list", "email = ?", new String[]{email.trim().toLowerCase()});
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rows > 0;
    }

    // 3. КРИТИЧЕСКИЙ МЕТОД: Проверка, находится ли пользователь в бане
    public boolean isUserBanned(String email) {
        if (email == null || email.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean isBanned = false;

        try (Cursor cursor = db.query(
                "ban_list",
                new String[]{"id"},
                "email = ?",
                new String[]{email.trim().toLowerCase()},
                null, null, null)) {

            // Если база нашла хотя бы одну строку — пользователь заблокирован
            isBanned = cursor.moveToFirst();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return isBanned;
    }

    public List<String> getBannedUsers() {
        List<String> bannedList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Запрашиваем только колонку email из таблицы ban_list, сортируем по времени блокировки (сначала свежие)
        try (Cursor cursor = db.query("ban_list", new String[]{"email"}, null, null, null, null, "timestamp DESC")) {
            if (cursor.moveToFirst()) {
                int emailIndex = cursor.getColumnIndex("email");
                if (emailIndex >= 0) {
                    do {
                        String email = cursor.getString(emailIndex);
                        if (email != null) {
                            bannedList.add(email);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bannedList;
    }

    /**
     * Полностью и безвозвратно удаляет чат и всю историю сообщений из SQLite базы данных.
     * Защищено от багов приведения типов (Type Mismatch) в современных Android SDK.
     *
     * @param chatId уникальный идентификатор удаляемого чата
     * @return true, если чат был успешно стерт из таблицы chats
     */
    public boolean deleteChatAndMessages(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;

        // Открываем атомарную транзакцию (оба удаления выполнятся строго вместе)
        db.beginTransaction();
        try {
            // 1. ИСПРАВЛЕНИЕ: Стираем все сообщения чата с принудительным приведением типов CAST
            db.delete(
                    "messages",
                    "chat_id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(chatId)}
            );

            // 2. ИСПРАВЛЕНИЕ: Стираем саму комнату чата с принудительным приведением типов CAST
            int rows = db.delete(
                    "chats",
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(chatId)}
            );

            // Фиксируем успех транзакции в SQLite
            db.setTransactionSuccessful();
            success = rows > 0;

            Log.d("MailLis_DB", "Чат " + chatId + " и его сообщения стерты. Результат: " + success);

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при удалении чата " + chatId, e);
            e.printStackTrace();
        } finally {
            // Обязательно закрываем транзакцию, возвращая базу в пул многопоточности WAL
            db.endTransaction();
        }

        return success;
    }

    /**
     * Переводит статус сообщения в состояние "Прочитано" (две галочки).
     */
    public void markMessageAsRead(String serverUid) {
        if (serverUid == null || serverUid.isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 2); // 2 = Две галочки
        db.update("messages", values, "server_uid = ?", new String[]{serverUid});

    }

    // Метод перевода сообщения в статус отправки (защита от повторной отправки параллельным потоком)
    public void markMessageAsSending(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 3); // 3 = Сообщение в процессе отправки прямо сейчас
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});
    }

    public void resetMessageStatusToUnsent(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("is_sent", 0); // Возвращаем в "Часики"
        db.update("messages", values, "id = ?", new String[]{String.valueOf(localMessageId)});

    }

    /**
     * Моментально возвращает общее количество сообщений, сохраненных в таблице messages.
     * Используется фоновой службой MailLisPushService для отслеживания появления новых входящих писем.
     *
     * @return общее число строк в таблице messages или 0 в случае пустоты или сбоя
     */
    public int getTotalMessagesCount() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int totalCount = 0;

        // Используем rawQuery для выполнения прямого и самого быстрого подсчета строк
        String sql = "SELECT COUNT(*) FROM messages";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            // Проверяем, вернула ли база данных результат (COUNT всегда возвращает 1 строку)
            if (cursor != null && cursor.moveToFirst()) {
                // Считываем числовое значение из самой первой (нулевой) колонки
                totalCount = cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критический сбой подсчета сообщений в getTotalMessagesCount()", e);
            e.printStackTrace();
        }

        return totalCount;
    }

    /**
     * Извлекает из SQLite самое последнее прилетевшее входящее сообщение (is_mine = 0).
     * Используется фоновой службой MailLisPushService для формирования текста всплывающего Push-уведомления.
     *
     * @return объект Message с заполненными данными отправителя и текста, либо null, если входящих сообщений еще нет
     */
    public Message getLastIncomingMessage() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Message lastMessage = null;

        // Ищем строго чужие сообщения (is_mine = 0), сортируем от новых к старым (DESC) и берем ровно 1 строку
        String sql = "SELECT * FROM messages WHERE is_mine = 0 ORDER BY timestamp DESC LIMIT 1";

        try (Cursor cursor = db.rawQuery(sql, null)) {
            // Проверяем, нашла ли база данных подходящую запись
            if (cursor != null && cursor.moveToFirst()) {

                // Узнаем индексы колонок строго один раз (для максимальной скорости)
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int statusIndex = cursor.getColumnIndex("is_sent");

                // Проверяем, что все базовые колонки присутствуют в таблице
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);

                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Собираем чистый объект сообщения
                    lastMessage = new Message(realId, sender, subject, text, time, false, serverUid, status);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении последнего входящего сообщения для Push", e);
            e.printStackTrace();
        }

        return lastMessage;
    }

}
