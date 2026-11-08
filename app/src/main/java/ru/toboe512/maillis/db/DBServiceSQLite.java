package ru.toboe512.maillis.db;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.KEY_REQUEST;
import static ru.toboe512.maillis.consts.Consts.KEY_RESPONSE;
import static ru.toboe512.maillis.consts.Consts.TEXT_MSG;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Contact;
import ru.toboe512.maillis.dto.MessageDto;

public class DBServiceSQLite implements DBService {

    // Переменные для работы с базой данных
    private final ChatDbHelper dbHelper;


    // Внутренний статический класс-держатель.
    // Он загрузится в память строго в тот момент, когда впервые вызовут метод getInstance()
    private static class Holder {
        private static DBServiceSQLite INSTANCE;
    }

    // Единственная глобальная точка доступа (Потокобезопасно на уровне JVM)
    public static DBServiceSQLite getInstance(Context context) {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new DBServiceSQLite(context);
        }
        return Holder.INSTANCE;
    }

    private DBServiceSQLite(Context cotext) {
        this.dbHelper = new ChatDbHelper(cotext.getApplicationContext());
    }

    public long saveContact(String name, String email) {
        if (email == null || email.trim().isEmpty()) {
            Log.w("MailLis_DB_SaveContact", "saveContact: отмена, передан пустой email.");
            return -1;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newRowId = -1;

        // 1. СТАНДАРТИЗАЦИЯ: Очищаем пробелы и жестко переводим Email в нижний регистр
        String normalizedEmail = email.trim().toLowerCase();
        String normalizedName = name != null ? name.trim() : normalizedEmail;

        // 2. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает пишущий поток от конфликтов с фоновой службой SyncRunnable
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: ПРОВЕРКА НА ДУБЛИКАТЫ (Исключаем дублирование записей)
            String checkSql = "SELECT id FROM contacts WHERE email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    newRowId = cursor.getLong(0);

                    db.setTransactionSuccessful(); // Фиксируем успех — цель достигнута, контакт в базе
                    Log.d("MailLis_DB_SaveContact", "Контакт " + normalizedEmail + " уже существует. Возвращаем ID: " + newRowId);
                    return newRowId;
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ И АТОМАРНАЯ ВСТАВКА ЧЕРЕЗ CONFLICT_IGNORE
            ContentValues values = new ContentValues();
            values.put("name", normalizedName);
            values.put("email", normalizedEmail);

            // Флаг CONFLICT_IGNORE на уровне Си-ядра страхует уникальность уникальных индексов UNIQUE(email)
            newRowId = db.insertWithOnConflict(
                    "contacts",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            if (newRowId != -1) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на физическом диске
                Log.i("MailLis_DB_SaveContact", "🎉 Успешно создан новый контакт. ID: " + newRowId + " для " + normalizedEmail);
            } else {
                Log.w("MailLis_DB_SaveContact", "Ошибка insert: база данных вернула -1 для " + normalizedEmail);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_SaveContact", "Критическая ошибка транзакции при сохранении контакта: " + normalizedEmail, e);
            e.printStackTrace();
            newRowId = -1;
        } finally {
            // 3. КРИТИЧЕСКИЙ ФИКС: Закрываем ТОЛЬКО транзакцию, высвобождая поток в пул WAL.
            // Саму базу db.close() НЕ вызываем, сохраняя пул соединений приложения живым!
            db.endTransaction();
        }

        return newRowId;
    }

    public List<Contact> getContacts() {
        List<Contact> contactList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ИСПРАВЛЕНИЕ: Добавляем явное приведение типов в подзапросе ban_list,
        // чтобы SQLite на Android 14+ не игнорировал фильтрацию исключений.
        String sql = "SELECT id, name, email FROM contacts " +
                "WHERE email NOT IN (SELECT CAST(email AS TEXT) FROM ban_list) " +
                "ORDER BY name ASC";

        // Использование try-with-resources гарантирует автоматическое закрытие Cursor
        try (Cursor cursor = db.rawQuery(sql, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                // ОПТИМИЗАЦИЯ: Узнаем индексы колонок строго один раз до начала цикла
                int idIndex = cursor.getColumnIndex("id");
                int nameIndex = cursor.getColumnIndex("name");
                int emailIndex = cursor.getColumnIndex("email");

                // Проверяем существование колонок в структуре таблицы
                if (idIndex >= 0 && nameIndex >= 0 && emailIndex >= 0) {
                    do {
                        int id = cursor.getInt(idIndex);
                        String name = cursor.getString(nameIndex);
                        String email = cursor.getString(emailIndex);

                        // СТРАХОВКА ОТ NullPointerException (NPE)
                        // Если в имени или почте Mail.ru лежат пустые/null значения,
                        // предотвращаем падение адаптера, заменяя их на пустые строки.
                        String safeName = name != null ? name.trim() : "Без имени";
                        String safeEmail = email != null ? email.trim().toLowerCase() : "";

                        if (!safeEmail.isEmpty()) {
                            // Создаем объект контакта.
                            // По умолчанию c.isSelected() будет false, но наш обновленный Single Choice
                            // адаптер сам свяжет его по ID при перерисовке списка ячеек!
                            Contact contact = new Contact(id, safeName, safeEmail);
                            contactList.add(contact);
                        }

                    } while (cursor.moveToNext());
                }
            }

            Log.d("MailLis_DB_Contacts", "Успешно выгружено контактов из SQLite (за вычетом бана): " + contactList.size());

        } catch (Exception e) {
            Log.e("MailLis_DB_Contacts", "Критическая ошибка при чтении списка контактов из базы", e);
            e.printStackTrace();
        }

        return contactList;
    }

    public Contact getContactById(int contactId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Contact contact = null;

        // Используем CAST для железной стабильности сопоставления типов в SQLite [source]
        String sql = "SELECT id, name, email FROM contacts WHERE id = CAST(? AS INTEGER) LIMIT 1";
        String[] selectionArgs = new String[]{String.valueOf(contactId)};

        // Конструкция try-with-resources гарантирует автоматическое закрытие курсора [source]
        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex("id");
                int nameIdx = cursor.getColumnIndex("name");
                int emailIdx = cursor.getColumnIndex("email");

                if (idIdx >= 0 && nameIdx >= 0 && emailIdx >= 0) {
                    int id = cursor.getInt(idIdx);
                    String name = cursor.getString(nameIdx);
                    String email = cursor.getString(emailIdx);

                    // Собираем объект контакта (подставьте ваш конструктор)
                    contact = new Contact(id,
                            name != null ? name.trim() : "",
                            email != null ? email.trim().toLowerCase() : "");
                }
            }
            Log.d("MailLis_DB_Contact", "getContactById для ID " + contactId + " -> " + (contact != null ? contact.getEmail() : "Не найден"));
        } catch (Exception e) {
            Log.e("MailLis_DB_Contact", "Критическая ошибка при поиске контакта по ID: " + contactId, e);
            e.printStackTrace();
        }

        return contact;
    }

    public String getContactNameByEmail(String email) {
        if (email == null || email.trim().isEmpty()) {// Подстраховка от краша
            return EMPTI_STRING;
        }

        String name = EMPTI_STRING;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT name FROM contacts WHERE email LIKE ? LIMIT 1";

        try (Cursor cursor = db.rawQuery(sql, new String[]{email.trim().toLowerCase()})) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex("name");
                if (nameIdx >= 0) {
                    name = cursor.getString(nameIdx);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return name;
    }

    public boolean deleteContact(int contactId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSuccess = false;

        // ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает пишущий поток от конфликтов с фоновой службой SyncRunnable
        db.beginTransactionNonExclusive();
        try {
            // УДАЛЯЕМ САМ КОНТАКТ ИЗ ТАБЛИЦЫ CONTACTS
            // Использование CAST(? AS INTEGER) гарантирует 100% совместимость с Android 14+
            int affectedRows = db.delete(
                    "contacts",
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(contactId)}
            );

            // Если строка физически исчезла с диска — коммитим транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения в файле базы
                isSuccess = true;
                Log.i("MailLis_DB_DelContact", "🎉 Контакт с ID " + contactId + " полностью удален со смартфона.");
            } else {
                Log.w("MailLis_DB_DelContact", "Контакт с ID " + contactId + " не найден в таблице contacts.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_DelContact", "Критическая ошибка транзакции при удалении контакта по ID: " + contactId, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public boolean deleteContact(String email) {
        if (email == null || email.trim().isEmpty()) {
            Log.w("MailLis_DB_DelContact", "deleteContact: отмена, передан пустой email.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean isSuccess = false;

        // ОТКРЫВАЕМ БЕЗОПАСНУЮ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому циклу SyncRunnable параллельно читать базу без крэшей и фризов
        db.beginTransactionNonExclusive();
        try {

            //  УДАЛЯЕМ САМ КОНТАКТ ИЗ ТАБЛИЦЫ contacts
            // Метод db.delete возвращает количество реально стертых строк на диске
            int affectedRows = db.delete("contacts", "email LIKE ?", new String[]{normalizedEmail});

            // Если на диске успешно стерлась хотя бы одна строка — коммитим транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Фиксируем успех на физическом накопителе
                isSuccess = true;
                Log.i("MailLis_DB_DelContact", "🎉 Контакт " + normalizedEmail + " и все его крипто-данные успешно удалены.");
            } else {
                Log.w("MailLis_DB_DelContact", "Контакт " + normalizedEmail + " не найден в таблице contacts.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_DelContact", "Критическая ошибка транзакции при удалении контакта: " + normalizedEmail, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 5. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public List<MessageDto> getAllMessages(long chatId, boolean isGroup) {
        List<MessageDto> messageDtoList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        String sql;
        String[] selectionArgs;

        if (isGroup) {
            // --- РЕЖИМ ГРУППЫ (Наш рабочий подзапрос) ---
            sql = "SELECT * FROM messages WHERE is_group_message = 1 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT remote_group_id FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1) OR " +
                    "  chat_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                    ") ORDER BY timestamp ASC";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId), String.valueOf(chatId)};
        } else {
            // ИСПРАВЛЕНИЕ ДЛЯ ОДИНОЧНЫХ ЧАТОВ:
            // Если активность получила remote-индентификатор или произошел сдвиг автоинкремента,
            // этот подзапрос свяжет историю по recipient_email, страхуя отображение своих сообщений!
            sql = "SELECT * FROM messages WHERE is_group_message = 0 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT id FROM chats WHERE recipient_email = " +
                    "    (SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1))" +
                    ") ORDER BY timestamp ASC";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId)};
        }

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {

                // Считываем индексы колонок один раз на самом верху (критическая оптимизация производительности)
                int idIdx = cursor.getColumnIndex("id");
                int senderIdx = cursor.getColumnIndex("sender_email");
                int textIdx = cursor.getColumnIndex("message_text");
                int subjectIdx = cursor.getColumnIndex("subject");
                int timeIdx = cursor.getColumnIndex("timestamp");
                int uidIdx = cursor.getColumnIndex("server_uid");
                int sentIdx = cursor.getColumnIndex("is_sent");
                int mineIdx = cursor.getColumnIndex("is_mine");
                int typeIdx = cursor.getColumnIndex("message_type");
                int pathIdx = cursor.getColumnIndex("file_path");

                // Проверяем существование главных структурных полей
                if (idIdx >= 0 && senderIdx >= 0 && textIdx >= 0 && timeIdx >= 0) {
                    do {
                        int id = cursor.getInt(idIdx);
                        String sender = cursor.getString(senderIdx);
                        String text = cursor.getString(textIdx);
                        long timestamp = cursor.getLong(timeIdx);

                        // Безопасное извлечение опциональных и добавленных колонок
                        String subject = subjectIdx >= 0 ? cursor.getString(subjectIdx) : "";

                        // ИСПРАВЛЕНО: Теперь используется строго правильная переменная индекса uidIdx
                        String serverUid = uidIdx >= 0 ? cursor.getString(uidIdx) : "";

                        // Конвертируем SQLite INTEGER флага в Java boolean структуры
                        boolean isMine = mineIdx >= 0 && cursor.getInt(mineIdx) == 1;
                        int isSentStatus = sentIdx >= 0 ? cursor.getInt(sentIdx) : 1;

                        // Новые медиа-поля
                        String messageType = typeIdx >= 0 ? cursor.getString(typeIdx) : TEXT_MSG;
                        String filePath = pathIdx >= 0 ? cursor.getString(pathIdx) : null;

                        // Собираем полноценный DTO-объект сообщения
                        MessageDto messageDto = new MessageDto(
                                id,
                                sender,
                                subject,
                                text,
                                timestamp,
                                isMine,
                                serverUid,
                                isSentStatus,
                                messageType,
                                filePath,
                                isGroup

                        );

                        messageDtoList.add(messageDto);

                    } while (cursor.moveToNext());
                }
            }
            Log.d("MailLis_DB", " getAllMessages: считано для Тет-а-Тет " + chatId + " -> " + messageDtoList.size());
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при чтении истории одиночного чата: " + chatId, e);
            e.printStackTrace();
        }

        return messageDtoList;
    }

    public int saveGenericMessage(long chatId, String myEmail, String subject, String text, boolean isMine, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long newId = -1;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает от взаимных блокировок при одновременной отправке SMTP и скачивании по IMAP
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ С ЗАЩИТОЙ ОТ КОЛЛИЗИЙ ID
            boolean isGroup = false;

            // КРИТИЧЕСКИЙ ФИКС: Сначала проверяем глобальный remote_group_id.
            // Если chatId — это большое уникальное число группы, оно 100% найдётся здесь!
            String checkGroupSql = "SELECT 1 FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId), String.valueOf(chatId)})) {
                if (groupCursor != null && groupCursor.moveToFirst()) {
                    isGroup = true; // Нашли совпадение в группах
                } else {
                    // Если в группах не нашли вообще ничего — проверяем таблицу одиночных чатов
                    String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                        isGroup = !(cursor != null && cursor.moveToFirst()); // Если нет в chats, фолбэк на false
                    }
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ С УЧЕТОМ СТРОГОЙ ТИПИЗАЦИИ СТРОК
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", myEmail != null ? myEmail.trim().toLowerCase() : "");
            values.put("message_text", text);
            values.put("subject", subject);
            values.put("timestamp", timestamp);
            values.put("is_mine", isMine ? 1 : 0);

            // Исходящие сообщения по умолчанию получают статус "Часики" (0), входящие — "Доставлено" (1)
            values.put("is_sent", isMine ? 0 : 1);

            // Поля структуры для обычного текста (используйте ваши константы TEXT_MSG)
            values.put("message_type", "TEXT");
            values.put("file_path", (String) null);

            // Записываем железно вычисленный контекст переписки (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // ШАГ 3: АТОМАРНАЯ ВСТАВКА И ЗАВЕРШЕНИЕ ТРАНЗАКЦИИ
            newId = db.insert("messages", null, values);

            if (newId != -1) {
                // Маркируем транзакцию успешной только если строка физически записалась на диск
                db.setTransactionSuccessful();
                Log.d("MailLis_DB_Secure", "Сообщение успешно записано в транзакции. Локальный ID: " + newId
                        + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Secure", "Критическая ошибка транзакции при сохранении сообщения через saveGenericMessage", e);
            e.printStackTrace();
            newId = -1;
        } finally {
            // Гарантированно закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return (int) newId;
    }

    public int saveMessage(long chatId, String senderEmail, String subject, String messageText, long currentTimestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int generatedId = -1;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет интерфейсу плавно записать сообщение, пока фоновый сервис сканирует почту Mail.ru
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: ИНТЕЛЛЕКТУАЛЬНОЕ АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ (ЗАЩИТА ОТ КОЛЛИЗИЙ)
            boolean isGroup = false;

            // КРИТИЧЕСКИЙ ФИКС: Сначала проверяем уникальный глобальный remote_group_id.
            // Если chatId совпал с номером группы участников — это гарантированно группа.
            String checkGroupSql = "SELECT 1 FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId), String.valueOf(chatId)})) {
                if (groupCursor != null && groupCursor.moveToFirst()) {
                    isGroup = true; // Нашли совпадение в групповых комнатах
                } else {
                    // Если совпадений в группах нет — проверяем таблицу одиночных чатов chats
                    String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                        isGroup = !(cursor != null && cursor.moveToFirst()); // Если нет в chats, то фолбэк на false
                    }
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ ДЛЯ ТАБЛИЦЫ MESSAGES
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail != null ? senderEmail.trim().toLowerCase() : "");
            values.put("message_text", messageText);
            values.put("subject", subject);
            values.put("timestamp", currentTimestamp);

            // Настройки для локального исходящего сообщения:
            values.put("is_mine", 1); // 1 = Сообщение моё (отрисуется справа)
            values.put("is_sent", 0); // 0 = Статус "Часики" 🕒 (ожидает SMTP отправки)

            // Дефолтные медиа-параметры (используйте вашу константу TEXT_MSG, если она объявлена в классе)
            values.put("message_type", "TEXT");
            values.put("file_path", (String) null); // Для текста путь к файлу всегда null

            // Записываем вычисленный реляционный флаг контекста (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // ШАГ 3: ЗАПИСЬ НА НАКОПИТЕЛЬ СМАРТФОНА
            long rowId = db.insert("messages", null, values);
            generatedId = (int) rowId;

            if (generatedId != -1) {
                // Коммитим изменения на диск только при успешном INSERT
                db.setTransactionSuccessful();
                Log.d("MailLis_DB_Save", "Исходящее сообщение записано в транзакции. ID: "
                        + generatedId + " (Контекст: " + (isGroup ? "ГРУППА" : "ТЕТ-А-ТЕТ") + ")");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Save", "Критическая ошибка транзакции при локальном сохранении сообщения для чата " + chatId, e);
            e.printStackTrace();
            generatedId = -1;
        } finally {
            // Гарантированно закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return generatedId;
    }

    public int saveGroupMessage(long groupId, String senderEmail, String subject, String messageText, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = -1;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет UI-потоку мгновенно записать сообщение, пока фоновый конвейер проверяет почту Mail.ru
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("chat_id", groupId); // Записываем сквозной ID, используемый активностью
            values.put("sender_email", senderEmail != null ? senderEmail.trim().toLowerCase() : "");
            values.put("message_text", messageText);
            values.put("subject", subject);
            values.put("timestamp", timestamp);

            // Настройки для локального исходящего сообщения группы
            values.put("is_mine", 1); // 1 = Наше сообщение (отрисуется справа)
            values.put("is_sent", 0); // 0 = Статус "Часики" 🕒 (ожидает SMTP отправки веерным слоем)

            // Дефолтные медиа-параметры (используйте вашу константу TEXT_MSG, если она объявлена в классе)
            values.put("message_type", "TEXT");
            values.put("file_path", (String) null);

            // ЖЕСТКАЯ ГАРАНТИЯ: Это сообщение 100% групповое!
            values.put("is_group_message", 1);

            // 2. ВСТАВЛЯЕМ ЗАПИСЬ В ТАБЛИЦУ MESSAGES
            rowId = db.insert("messages", null, values);

            if (rowId != -1) {
                // Фиксируем транзакцию на физическом диске только при успешной вставке
                db.setTransactionSuccessful();
                Log.d("MailLis_DB_GroupSave", "Групповое исходящее сообщение записано в транзакции. ID: " + rowId);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_GroupSave", "Критическая ошибка транзакции при сохранении группового сообщения для ID: " + groupId, e);
            e.printStackTrace();
            rowId = -1;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, освобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return (int) rowId;
    }

    public MessageDto getMessage(long localMessageId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto messageDto = null;

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
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");

                // Проверяем существование критически важных колонок
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int isSend = cursor.getInt(isSendIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);
                    int isGroup = cursor.getInt(isGroupIndex);

                    // Безопасное чтение необязательных текстовых колонок
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Упрощенное и безопасное считывание бита is_mine напрямую из базы
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Создаем объект сообщения
                    messageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                            serverUid, isSend, messageType, filePath, isGroup != 0);
                }
            }
        }

        return messageDto;
    }

    public boolean markMessageAsSent(int localMessageId, String serverUid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isUpdated = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет сетевому слою обновить статус, не блокируя секундный таймер чтения UI
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("is_sent", 1); // Код 1 = Одна галочка "✓" на экране мессенджера

            if (serverUid != null && !serverUid.trim().isEmpty()) {
                values.put("server_uid", serverUid.trim());
            }

            // 2. ИСПРАВЛЕНИЕ ТИПОВ: Используем CAST(? AS INTEGER) для 100% совместимости с Android 14+
            // Метод db.update возвращает точное количество измененных строк на диске
            int affectedRows = db.update(
                    "messages",
                    values,
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localMessageId)}
            );

            // Транзакция считается успешной только если строка физически обновилась
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения в файле базы
                isUpdated = true;
                Log.i("MailLis_DB_Status", "🎉 Успешно! markMessageAsSent: Обновлен статус и UID для ID: " + localMessageId);
            } else {
                Log.w("MailLis_DB_Status", "Предупреждение: сообщение с ID " + localMessageId + " не найдено. Статус не изменен.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Status", "Критическая ошибка транзакции при обновлении статуса для ID: " + localMessageId, e);
            e.printStackTrace();
            isUpdated = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isUpdated;
    }

    public List<ChatPreview> loadChats() {
        List<ChatPreview> chatList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        String sql =
                // --- ЧАСТЬ 1: ОДИНОЧНЫЕ ЧАТЫ (is_group_message = 0) ---
                "SELECT " +
                        "  c.id AS chat_id, " +
                        "  c.title AS chat_name, " +
                        "  m.message_text AS text_preview, " +
                        "  m.subject AS subject_preview, " +
                        "  COALESCE(m.timestamp, 0) AS sorted_time, " +
                        "  0 AS is_group_flag " +
                        "FROM chats c " +
                        "LEFT JOIN messages m ON m.id = ( " +
                        "  SELECT id FROM messages WHERE chat_id = c.id AND is_group_message = 0 " +
                        "  ORDER BY timestamp DESC LIMIT 1 " +
                        ") " +

                        "UNION ALL " +

                        // --- ЧАСТЬ 2: ГРУППОВЫЕ ЧАТЫ (С полным реляционным сопоставлением ID) ---
                        "SELECT " +
                        "  g.remote_group_id AS chat_id, " + // Отдаем глобальный ID для P2P-синхронизации
                        "  g.group_name AS chat_name, " +
                        "  m_g.message_text AS text_preview, " +
                        "  m_g.subject AS subject_preview, " +
                        "  COALESCE(m_g.timestamp, 0) AS sorted_time, " +
                        "  1 AS is_group_flag " +
                        "FROM group_chats g " +
                        "LEFT JOIN messages m_g ON m_g.id = ( " +
                        "  SELECT id FROM messages WHERE is_group_message = 1 AND ( " +
                        "    chat_id = g.id OR chat_id = g.remote_group_id " + // Исправлен маппинг ID создателя и участников!
                        "  ) ORDER BY timestamp DESC LIMIT 1 " +
                        ") " +

                        // ---ОБЩАЯ СОРТИРОВКА: Чаты со свежей активностью всегда наверху ---
                        "ORDER BY sorted_time DESC";

        // Автоматическое закрытие курсора через try-with-resources исключает Memory Leaks
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (cursor != null && cursor.moveToFirst()) {

                int idIdx = cursor.getColumnIndex("chat_id");
                int nameIdx = cursor.getColumnIndex("chat_name");
                int textIdx = cursor.getColumnIndex("text_preview");
                int subjectIdx = cursor.getColumnIndex("subject_preview");
                int groupIdx = cursor.getColumnIndex("is_group_flag");

                if (idIdx >= 0 && nameIdx >= 0 && textIdx >= 0 && subjectIdx >= 0 && groupIdx >= 0) {
                    do {
                        long id = cursor.getLong(idIdx);
                        String name = cursor.getString(nameIdx);
                        String rawText = cursor.getString(textIdx);
                        String subject = cursor.getString(subjectIdx);
                        boolean isGroup = cursor.getInt(groupIdx) == 1;

                        String finalMessageText = isGroup ? "Группа создана" : "Нет сообщений";

                        if (rawText != null) {
                            finalMessageText = rawText;
                            // Проверяем на служебные заголовки рукопожатий (для одиночных чатов)
                            if (subject != null && !isGroup) {
                                if (subject.contains(KEY_REQUEST) || subject.contains(KEY_RESPONSE)) {
                                    finalMessageText = "Чат создан";
                                }
                            }
                        }

                        var preview = new ChatPreview(id, name, finalMessageText, isGroup);
                        chatList.add(preview);

                    } while (cursor.moveToNext());
                }
            }
            Log.d("MailLis_DB_Union", "Выгружено чатов для главного экрана: " + chatList.size());
        } catch (Exception e) {
            Log.e("MailLis_DB_Union", "Критическая ошибка выполнения UNION запроса в loadChats()", e);
            e.printStackTrace();
        }

        return chatList;
    }

    public MessageDto getLastMessage(long chatId, boolean isGroup) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto lastMessageDto = null;

        String sql;
        String[] selectionArgs;

        if (isGroup) {
            // Ищем последнее сообщение по переданному chatId,
            // но ТАКЖЕ заглядываем в таблицу group_chats, чтобы связать remote_group_id!
            // Это гарантирует появление превью текста на смартфонах всех участников.
            sql = "SELECT * FROM messages WHERE is_group_message = 1 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT remote_group_id FROM group_chats WHERE id = CAST(? AS INTEGER) LIMIT 1) OR " +
                    "  chat_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                    ") ORDER BY timestamp DESC LIMIT 1";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId), String.valueOf(chatId)};
        } else {
            // ДЛЯ ОДИНОЧНЫХ ЧАТОВ: Защита от сдвига ID через сопоставление по email
            sql = "SELECT * FROM messages WHERE is_group_message = 0 AND (" +
                    "  chat_id = CAST(? AS INTEGER) OR " +
                    "  chat_id = (SELECT id FROM chats WHERE recipient_email = " +
                    "    (SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1))" +
                    ") ORDER BY timestamp DESC LIMIT 1";

            selectionArgs = new String[]{String.valueOf(chatId), String.valueOf(chatId)};
        }

        // Использование try-with-resources гарантирует 100% закрытие курсора при любых условиях
        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {

                // Считываем индексы колонок строго один раз для максимальной производительности
                int idIndex = cursor.getColumnIndex("id");
                int senderIndex = cursor.getColumnIndex("sender_email");
                int textIndex = cursor.getColumnIndex("message_text");
                int timeIndex = cursor.getColumnIndex("timestamp");
                int subjectIndex = cursor.getColumnIndex("subject");
                int uidIndex = cursor.getColumnIndex("server_uid");
                int isMineIndex = cursor.getColumnIndex("is_mine");
                int typeIndex = cursor.getColumnIndex("message_type");
                int pathIndex = cursor.getColumnIndex("file_path");
                int isSentIndex = cursor.getColumnIndex("is_sent");

                // Проверяем существование базовых структурных полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);

                    // Безопасное считывание опциональных полей переписки с защитой от Null
                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : "";
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : "";
                    boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                    // Настройка констант медиа-полей (используйте ваши переменные, напр. TEXT_MSG)
                    String messageType = typeIndex >= 0 ? cursor.getString(typeIndex) : "TEXT";
                    String filePath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
                    int isSent = isSentIndex >= 0 ? cursor.getInt(isSentIndex) : 1;

                    // Собираем полноценный DTO-объект сообщения со всеми атрибутами
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                            serverUid, isSent, messageType, filePath, isGroup);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка извлечения последнего сообщения для чата: " + chatId, e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    public long saveChat(String title, String targetEmail) {
        if (targetEmail == null || targetEmail.trim().isEmpty()) {
            Log.w("MailLis_DB_Chat", "saveChat: отмена, передан пустой targetEmail.");
            return -1;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long chatId = -1;

        // Стандартизируем Email: очищаем пробелы и переводим в нижний регистр
        String normalizedEmail = targetEmail.trim().toLowerCase();
        String normalizedTitle = title != null ? title.trim() : normalizedEmail;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает от взаимных блокировок диска с фоновым сервисом IMAP/SMTP
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: ЗАЩИТА ОТ ДУБЛИКАТОВ — ПРОВЕРЯЕМ СУЩЕСТВОВАНИЕ ЧАТА
            String checkSql = "SELECT id FROM chats WHERE recipient_email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    chatId = cursor.getLong(0);

                    // Фиксируем успех транзакции, так как мы успешно нашли и отдаем легитимный ID
                    db.setTransactionSuccessful();
                    Log.d("MailLis_DB_Chat", "Чат с " + normalizedEmail + " уже существует. Возвращаем ID: " + chatId);
                    return chatId;
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ И АТОМАРНАЯ ВСТАВКА
            ContentValues values = new ContentValues();
            values.put("recipient_email", normalizedEmail);
            values.put("title", normalizedTitle);

            // Вставляем с игнорированием конфликтов на уровне SQLite ядра
            chatId = db.insertWithOnConflict(
                    "chats",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            // Проверяем результат вставки. Если -1 — значит произошел внутренний конфликт SQLite
            if (chatId != -1) {
                db.setTransactionSuccessful(); // Намертво фиксируем создание чата на флеш-памяти
                Log.i("MailLis_DB_Chat", "🎉 Успешно создан новый одиночный чат. ID: " + chatId + " для " + normalizedEmail);
            } else {
                Log.w("MailLis_DB_Chat", "Предупреждение: insertWithOnConflict вернул -1 для " + normalizedEmail);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Chat", "Критический сбой транзакции метода saveChat для email: " + normalizedEmail, e);
            e.printStackTrace();
            chatId = -1;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return chatId;
    }

    public long getChatIdByRecipientEmail(String email) {
        if (email == null || email.isEmpty()) {
            return -1;
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        long chatId = -1;

        // 1. Принудительно очищаем пробелы и переводим в нижний регистр для стандартизации поиска
        String normalizedEmail = email.trim().toLowerCase();

        // 2. Делаем выборку из таблицы chats. Оператор LIKE гарантирует регистронезависимость.
        // Ограничение LIMIT 1 сообщает SQLite, что нам нужна ровно одна строка (экономит ресурсы).
        String sql = "SELECT id FROM chats WHERE recipient_email LIKE ? LIMIT 1";

        // Используем try-with-resources для автоматического и гарантированного закрытия Cursor
        try (Cursor cursor = db.rawQuery(sql, new String[]{normalizedEmail})) {

            // Проверяем, нашла ли база данных хотя бы одну строку
            if (cursor != null && cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex("id");
                if (idIndex >= 0) {
                    // Извлекаем реальное числовое значение ID чата из SQLite (тип long)
                    chatId = cursor.getLong(idIndex);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при поиске одиночного chatId по email: " + normalizedEmail, e);
            e.printStackTrace();
        }

        return chatId;
    }

    @Override
    public boolean deleteMessage(int msgId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isDeleted = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет UI-потоку массово удалять сообщения без фризов и конфликтов с SyncRunnable
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: ЗАЩИТА ОТ МУСОРА — ИЩЕМ И СТИРАЕМ СВЯЗАННЫЙ МЕДИАФАЙЛ (ФОТО)
            String findFileSql = "SELECT file_path FROM messages WHERE id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(findFileSql, new String[]{String.valueOf(msgId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    int pathIdx = cursor.getColumnIndex("file_path");
                    if (pathIdx >= 0) {
                        String filePath = cursor.getString(pathIdx);
                        if (filePath != null && !filePath.trim().isEmpty()) {
                            File mediaFile = new File(filePath);
                            if (mediaFile.exists()) {
                                boolean fileDeleted = mediaFile.delete();
                                Log.d("MailLis_DB_DelMsg", "Связанный медиафайл удален: " + fileDeleted + " (" + filePath + ")");
                            }
                        }
                    }
                }
            }

            // ШАГ 2: УДАЛЕНИЕ СТРОКИ ИЗ ТАБЛИЦЫ MESSAGES С КОНТРОЛЕМ ТИПОВ
            // Используем CAST(? AS INTEGER) для 100% совместимости с Android 14+
            int affectedRows = db.delete(
                    "messages",
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(msgId)}
            );

            // Если строка физически исчезла с диска — коммитим транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем удаление на флеш-памяти
                isDeleted = true;
                Log.d("MailLis_DB_DelMsg", "Сообщение ID: " + msgId + " успешно удалено из SQLite.");
            } else {
                Log.w("MailLis_DB_DelMsg", "Сообщение с ID " + msgId + " не найдено в базе данных.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_DelMsg", "Критическая ошибка транзакции при удалении сообщения ID: " + msgId, e);
            e.printStackTrace();
            isDeleted = false;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая поток в пул WAL
            db.endTransaction();
        }

        return isDeleted;
    }


    public boolean saveIncomingMessage(long chatId, String senderEmail, String subject, String messageText,
                                       long currentTimestamp, String serverUid, String type, String imgPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSaved;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает пишущий поток от коллизий с секундным таймером и кнопкой отправки интерфейса
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: АВТО-ОПРЕДЕЛЕНИЕ ТИПА КОМНАТЫ С ЗАЩИТОЙ ОТ КОЛЛИЗИЙ ID
            boolean isGroup;

            // КРИТИЧЕСКИЙ ФИКС: Сначала проверяем глобальный remote_group_id.
            // Если chatId совпал с номером группы участников — это гарантированно группа.
            String checkGroupSql = "SELECT 1 FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor groupCursor = db.rawQuery(checkGroupSql, new String[]{String.valueOf(chatId), String.valueOf(chatId)})) {
                if (groupCursor != null && groupCursor.moveToFirst()) {
                    isGroup = true; // Нашли совпадение в групповых комнатах
                } else {
                    // Если совпадений в группах нет — проверяем таблицу одиночных чатов chats
                    String checkPrivateSql = "SELECT 1 FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";
                    try (Cursor cursor = db.rawQuery(checkPrivateSql, new String[]{String.valueOf(chatId)})) {
                        isGroup = !(cursor != null && cursor.moveToFirst()); // Если нет в chats, то фолбэк на false
                    }
                }
            }

            // ШАГ 2: СБОРКА ДАННЫХ ДЛЯ ТАБЛИЦЫ MESSAGES
            ContentValues values = new ContentValues();
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail != null ? senderEmail.trim().toLowerCase() : "");
            values.put("subject", subject);
            values.put("message_text", messageText);
            values.put("timestamp", currentTimestamp);
            values.put("server_uid", serverUid != null ? serverUid.trim() : "");

            values.put("is_mine", 0); // 0 = Входящее сообщение от собеседника (отрисуется слева)
            values.put("is_sent", 1); // 1 = Сообщение уже успешно доставлено через сеть Mail.ru

            // Записываем железно вычисленный контекст переписки (0 или 1)
            values.put("is_group_message", isGroup ? 1 : 0);

            // Защита от NullPointerException при использовании констант
            values.put("message_type", type != null ? type : "TEXT");
            values.put("file_path", imgPath);

            // ШАГ 3: АТОМАРНАЯ ВСТАВКА С ИГНОРИРОВАНИЕМ КОНФЛИКТОВ И ЗАВЕРШЕНИЕ
            // Если server_uid уже существует в базе (повторный фоновый цикл скачивания),
            // SQLite просто пропустит этот INSERT, вернув -1, и переписка не задроится.
            long rowId = db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            isSaved = (rowId != -1);

            if (isSaved) {
                // Фиксируем транзакцию на диске только если строка физически записалась
                db.setTransactionSuccessful();
                Log.d("MailLis_DB_Save", "Входящее сообщение успешно сохранено. ID: " + rowId
                        + " (Тип: " + (isGroup ? "ГРУППА" : "ОДИНОЧНЫЙ ЧАТ") + ")");
            } else {
                // Если вернулся -1 при отсутствии Exception — это легитимный пропуск дубликата
                db.setTransactionSuccessful(); // Все равно коммитим (закрываем транзакцию без отката)
                Log.w("MailLis_DB_Save", "Пропущено сохранение дубликата сообщения с UID: " + serverUid);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Save", "Критическая ошибка транзакции при сохранении входящего сообщения с UID: " + serverUid, e);
            e.printStackTrace();
            isSaved = false;
        } finally {
            // Гарантированно закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSaved;
    }

    public boolean saveIncomingGroupMessage(long chatId, String senderEmail, String subject, String messageText,
                                            long currentTimestamp, String serverUid, String type, String imgPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSaved;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому конвейеру писать пачки писем, не блокируя секундный таймер UI-потока
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();

            // Привязываем сообщение к сквозному глобальному ID группы
            values.put("chat_id", chatId);
            values.put("sender_email", senderEmail != null ? senderEmail.trim().toLowerCase() : "");
            values.put("subject", subject);
            values.put("message_text", messageText);
            values.put("timestamp", currentTimestamp);
            values.put("server_uid", serverUid != null ? serverUid.trim() : "");

            // Входящее сообщение от другого участника группы
            values.put("is_mine", 0); // 0 = Чужое (отрисуется слева)
            values.put("is_sent", 1); // 1 = Доставлено (уже прошло через сеть Mail.ru)

            // ЖЕСТКАЯ ГАРАНТИЯ КОНТЕКСТА: Сообщение на 100% является групповым
            values.put("is_group_message", 1);

            // Настройка медиа-параметров (для фото или текста)
            values.put("message_type", type != null ? type : "TEXT");
            values.put("file_path", imgPath);

            // 2. АТОМАРНАЯ ВСТАВКА С ФЛАГОМ ИГНОРИРОВАНИЯ КОНФЛИКТОВ
            // Защищает от двоения: если server_uid уже существует, SQLite вернет -1, не ломая UI
            long rowId = db.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            isSaved = (rowId != -1);

            if (isSaved) {
                // Фиксируем транзакцию на диске только при успешной вставке
                db.setTransactionSuccessful();
                Log.d("MailLis_DB_Group", "Входящее групповое сообщение успешно сохранено. ID: " + rowId + " для группы: " + chatId);
            } else {
                // Если вернулся -1 при отсутствии Exception — это легитимный пропуск дубликата.
                // Все равно коммитим, чтобы корректно и без отката закрыть транзакцию в WAL.
                db.setTransactionSuccessful();
                Log.w("MailLis_DB_Group", "Пропущено сохранение дубликата группового сообщения с UID: " + serverUid);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка транзакции при сохранении входящего группового сообщения", e);
            e.printStackTrace();
            isSaved = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSaved;
    }

    @Override
    public boolean updateServerUid(String generatedServerUid, int localMessageId) {
        // Жесткая валидация входных данных на null и пустоту
        if (generatedServerUid == null || generatedServerUid.trim().isEmpty()) {
            android.util.Log.w("MailLis_DB_Secure", "updateServerUid: отмена, передан пустой UID.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSuccess = false;

        // Обертывание в транзакцию гарантирует атомарность и защиту от многопоточных коллизий
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("server_uid", generatedServerUid.trim());

            // CAST(? AS INTEGER) гарантирует пуленепробиваемый апдейт на Android 14+.
            // Метод db.update возвращает количество реально измененных строк на диске.
            int affectedRows = db.update(
                    "messages",
                    values,
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localMessageId)}
            );

            // Если изменилась ровно 1 строка — транзакция успешна
            if (affectedRows > 0) {
                db.setTransactionSuccessful();
                isSuccess = true;
                Log.d("MailLis_DB_Secure", "Успешно обновлен UID для сообщения ID: " + localMessageId);
            } else {
                Log.w("MailLis_DB_Secure", "Предупреждение: сообщение с ID " + localMessageId + " не найдено для обновления UID.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Secure", "Критическая ошибка транзакции при обновлении server_uid для ID: " + localMessageId, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            db.endTransaction(); // Гарантированно закрываем транзакцию, возвращая поток в пул WAL
        }

        return isSuccess;
    }


    public byte[] getPublicKey(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] publicKey = null;

        // СЛОЖНЫЙ СВЕРХБЫСТРЫЙ SQL-ЗАПРОС:
        // Мы берем строку из chats, по её recipient_email соединяем с public_keys
        // и забираем бинарный BLOB ключа. CAST(? AS INTEGER) защищает от Type Mismatch.
        String sql = "SELECT p.peer_public_key FROM chats c " +
                "JOIN public_keys p ON c.recipient_email = p.email " +
                "WHERE c.id = CAST(? AS INTEGER) LIMIT 1";

        // Использование try-with-resources гарантирует автоматическое закрытие Cursor
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {

            // Так как ID уникален, проверяем строго одну первую запись
            if (cursor != null && cursor.moveToFirst()) {
                int publicKeyIndex = cursor.getColumnIndex("peer_public_key");
                if (publicKeyIndex >= 0) {
                    // Считываем массив байт (BLOB) публичного ключа Tink
                    publicKey = cursor.getBlob(publicKeyIndex);
                }
            }

        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при извлечении публичного ключа по chatId: " + chatId, e);
            e.printStackTrace();
        }

        return publicKey;
    }

    public List<MessageDto> getUnsentMessages() {
        List<MessageDto> unsentList = new ArrayList<>();
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
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");


                // Проверяем существование критически важных для структуры полей
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    do {
                        int realId = cursor.getInt(idIndex);
                        String sender = cursor.getString(senderIndex);
                        String text = cursor.getString(textIndex);
                        long time = cursor.getLong(timeIndex);
                        int status = cursor.getInt(statusIndex);
                        String messageType = cursor.getString(messageTypeIndex);
                        String filePath = cursor.getString(filePathIndex);
                        int isGroup = cursor.getInt(isGroupIndex);

                        // Безопасное чтение необязательных текстовых колонок
                        String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                        String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                        // Упрощенное и безопасное считывание флага из базы
                        boolean isMine = isMineIndex >= 0 && cursor.getInt(isMineIndex) == 1;

                        // Создаем чистый объект сообщения
                        var messageDto = new MessageDto(realId, sender, subject, text, time, isMine,
                                serverUid, status, messageType, filePath, isGroup != 0);
                        unsentList.add(messageDto);

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

    public boolean banUser(String email) {
        if (email == null || email.trim().isEmpty()) {
            Log.w("MailLis_DB_Ban", "banUser: отмена, передан пустой email.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean isSuccess = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает от взаимных блокировок диска с фоновым сервисом SyncRunnable
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: ПРОВЕРЯЕМ, НЕТ ЛИ ПОЛЬЗОВАТЕЛЯ УЖЕ В БАН-ЛИСТЕ
            String checkSql = "SELECT 1 FROM ban_list WHERE email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    db.setTransactionSuccessful(); // Фиксируем успех, так как цель достигнуть — юзер в бане
                    Log.d("MailLis_DB_Ban", "Пользователь " + normalizedEmail + " уже находится в бан-листе.");
                    return true;
                }
            }

            // ШАГ 2: КАСКАДНАЯ ОЧИСТКА — УДАЛЯЕМ КЭШ ПУБЛИЧНЫХ КЛЮЧЕЙ TINK
            db.delete("public_keys", "email LIKE ?", new String[]{normalizedEmail});

            // ШАГ 3: СБОРКА ДАННЫХ И АТОМАРНАЯ ВСТАВКА В BAN_LIST
            ContentValues values = new ContentValues();
            values.put("email", normalizedEmail);
            values.put("timestamp", System.currentTimeMillis());

            long rowId = db.insertWithOnConflict(
                    "ban_list",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            if (rowId != -1) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на флеш-памяти
                isSuccess = true;
                Log.i("MailLis_DB_Ban", "🔒 Пользователь " + normalizedEmail + " успешно заблокирован и внесен в ban_list.");
            } else {
                Log.w("MailLis_DB_Ban", "Предупреждение: insertWithOnConflict вернул -1 для " + normalizedEmail);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Ban", "Критическая ошибка транзакции при блокировке пользователя: " + normalizedEmail, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public boolean unbanUser(String email) {
        if (email == null || email.trim().isEmpty()) {
            Log.w("MailLis_DB_Unban", "unbanUser: отмена, передан пустой email.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean isSuccess = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет UI-потоку мгновенно применить изменения, не конфликтуя с фоновым IMAP-сервисом
        db.beginTransactionNonExclusive();
        try {
            // 2. Используем оператор LIKE для стандартизированного удаления строк
            // Метод db.delete возвращает количество реально стертых строк на диске
            int affectedRows = db.delete(
                    "ban_list",
                    "email LIKE ?",
                    new String[]{normalizedEmail}
            );

            // Если из таблицы ban_list физически исчезла запись — коммитим транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения в логе WAL
                isSuccess = true;
                Log.i("MailLis_DB_Unban", "🔓 Пользователь " + normalizedEmail + " успешно разблокирован и удален из ban_list.");
            } else {
                Log.w("MailLis_DB_Unban", "Пользователь " + normalizedEmail + " не найден в чёрном списке.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Unban", "Критическая ошибка транзакции при разблокировке пользователя: " + normalizedEmail, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

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
        try (Cursor cursor = db.query("ban_list", new String[]{"email"},
                null, null, null, null, "timestamp DESC")) {
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

    public boolean deleteChatAndMessages(long chatId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет UI-потоку мгновенно стереть чат, не конфликтуя с фоновым сервисом SyncRunnable
        db.beginTransactionNonExclusive();
        try {

            // ШАГ 1: УДАЛЕНИЕ СВЯЗАННЫХ СООБЩЕНИЙ ИЗ ТАБЛИЦЫ MESSAGES
            // Стираем все личные сообщения (is_group_message = 0), привязанные к этому chatId
            db.delete(
                    "messages",
                    "chat_id = CAST(? AS INTEGER) AND is_group_message = 0",
                    new String[]{String.valueOf(chatId)}
            );
            Log.d("MailLis_DB_DelChat", "История личных сообщений чата " + chatId + " успешно очищена.");

            // ШАГ 2: УДАЛЕНИЕ САМОЙ КОМНАТЫ ИЗ ТАБЛИЦЫ CHATS
            int rows = db.delete(
                    "chats",
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(chatId)}
            );

            // Если строка чата физически исчезла с диска — коммитим транзакцию
            if (rows > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на флеш-памяти
                success = true;
                Log.i("MailLis_DB_DelChat", "🎉 Одиночный чат " + chatId + " полностью удален с устройства.");
            } else {
                Log.w("MailLis_DB_DelChat", "Предупреждение: чат с ID " + chatId + " не найден в таблице chats.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_DelChat", "Критическая ошибка транзакции при удалении чата " + chatId, e);
            e.printStackTrace();
            success = false;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return success;
    }

    public boolean deleteGroupMembersByRemoteId(long remoteGroupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isDeleted = false;

        // Открываем транзакцию для безопасной и атомарной работы с диском
        db.beginTransaction();
        try {
            // 1. Находим локальный id группы в таблице group_chats по её remote_group_id
            long localGroupId = -1;
            String findLocalIdSql = "SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1";

            try (Cursor cursor = db.rawQuery(findLocalIdSql, new String[]{String.valueOf(remoteGroupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localGroupId = cursor.getLong(0);
                }
            }

            // 2. Если локальный ID успешно найден, очищаем таблицу участников
            if (localGroupId != -1) {
                // Используем прямой execSQL с CAST для защиты структуры типов на Android 14+
                db.execSQL("DELETE FROM group_members WHERE group_id = CAST(? AS INTEGER)",
                        new Object[]{localGroupId});

                db.setTransactionSuccessful(); // Фиксируем успех транзакции
                isDeleted = true;
                Log.d("MailLis_DB_Members", "Участники группы (Remote ID: " + remoteGroupId + ") успешно удалены.");
            } else {
                Log.w("MailLis_DB_Members", "Не удалось удалить участников: группа с Remote ID " + remoteGroupId + " не найдена.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Members", "Критическая ошибка при удалении участников группы по Remote ID: " + remoteGroupId, e);
            e.printStackTrace();
            isDeleted = false;
        } finally {
            db.endTransaction(); // Закрываем транзакцию
        }

        return isDeleted;
    }

    public boolean markMessageAsRead(String serverUid) {
        if (serverUid == null || serverUid.trim().isEmpty()) {
            Log.w("MailLis_DB_ReadStatus", "markMessageAsRead: отмена, передан пустой serverUid.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String cleanedUid = serverUid.trim();
        boolean isUpdated = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновой службе менять статусы галочек, не блокируя секундный таймер чтения ChatActivity
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("is_sent", 2); // Код 2 = Две галочки "✓✓" (Прочитано другом)

            // 2. ИСПРАВЛЕНИЕ: Используем оператор LIKE вместо "=" для защиты от скрытых пробелов/символов
            // Метод db.update возвращает количество реально измененных строк на диске
            int affectedRows = db.update(
                    "messages",
                    values,
                    "server_uid LIKE ?",
                    new String[]{cleanedUid}
            );

            // Фиксируем транзакцию только если строка физически обновилась
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво записываем изменения в лог WAL
                isUpdated = true;
                Log.d("MailLis_DB_ReadStatus", "🎉 Успешно! markMessageAsRead: Сообщение с UID " + cleanedUid + " помечено как прочитано (✓✓).");
            } else {
                Log.w("MailLis_DB_ReadStatus", "Сообщение с UID " + cleanedUid + " не найдено в таблице messages.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_ReadStatus", "Критическая ошибка транзакции при обновлении статуса прочтения для UID: " + cleanedUid, e);
            e.printStackTrace();
            isUpdated = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая поток в пул многопоточности WAL
            db.endTransaction();
        }

        return isUpdated;
    }

    public boolean markMessageAsSending(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isUpdated = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому потоку ретрая менять статусы, не мешая плавному чтению UI
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("is_sent", 3); // Код 3 = Песочные часы "⏳" на экране чата

            // 2. ИСПРАВЛЕНИЕ: Используем CAST(? AS INTEGER) для 100% совместимости с Android 14+
            // Метод db.update возвращает количество реально измененных строк на диске
            int affectedRows = db.update(
                    "messages",
                    values,
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localMessageId)}
            );

            // Если строка физически обновилась на диске смартфона — фиксируем транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво записываем изменения в лог WAL
                isUpdated = true;
                Log.d("MailLis_DB_Status", "⏳ Статус 'В процессе отправки' (3) успешно выставлен для сообщения ID: " + localMessageId);
            } else {
                Log.w("MailLis_DB_Status", "Предупреждение: сообщение с ID " + localMessageId + " не найдено. Статус не изменен.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Status", "Критическая ошибка транзакции при выставлении статуса отправки для ID: " + localMessageId, e);
            e.printStackTrace();
            isUpdated = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул многопоточности WAL
            db.endTransaction();
        }

        return isUpdated;
    }

    public boolean resetMessageStatusToUnsent(int localMessageId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isUpdated = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому потоку ретрая менять статусы, не мешая плавному чтению UI
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("is_sent", 0); // Код 0 = Снова часики "🕒" на экране чата

            // 2. ИСПРАВЛЕНИЕ: Используем CAST(? AS INTEGER) для 100% совместимости с Android 14+
            int affectedRows = db.update(
                    "messages",
                    values,
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localMessageId)}
            );

            // Если строка физически обновилась на диске смартфона — фиксируем транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво записываем изменения в лог WAL
                isUpdated = true;
                Log.w("MailLis_DB_Status", "Сеть упала. resetMessageStatusToUnsent для ID: " + localMessageId);
            } else {
                Log.w("MailLis_DB_Status", "Предупреждение: сообщение с ID " + localMessageId + " не найдено. Статус не изменен.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Status", "Критическая ошибка транзакции при сбросе статуса для ID: " + localMessageId, e);
            e.printStackTrace();
            isUpdated = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул многопоточности WAL
            db.endTransaction();
        }

        return isUpdated;
    }

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

    public MessageDto getLastIncomingMessage() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        MessageDto lastMessageDto = null;

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
                int messageTypeIndex = cursor.getColumnIndex("message_type");
                int filePathIndex = cursor.getColumnIndex("file_path");
                int isGroupIndex = cursor.getColumnIndex("is_group_message");

                // Проверяем, что все базовые колонки присутствуют в таблице
                if (idIndex >= 0 && senderIndex >= 0 && textIndex >= 0 && timeIndex >= 0 && statusIndex >= 0 &&
                        messageTypeIndex >= 0 && filePathIndex >= 0 && isGroupIndex >= 0) {
                    int realId = cursor.getInt(idIndex);
                    String sender = cursor.getString(senderIndex);
                    String text = cursor.getString(textIndex);
                    long time = cursor.getLong(timeIndex);
                    int status = cursor.getInt(statusIndex);
                    String messageType = cursor.getString(messageTypeIndex);
                    String filePath = cursor.getString(filePathIndex);
                    int isGroup = cursor.getInt(isGroupIndex);

                    String subject = subjectIndex >= 0 ? cursor.getString(subjectIndex) : EMPTI_STRING;
                    String serverUid = uidIndex >= 0 ? cursor.getString(uidIndex) : EMPTI_STRING;

                    // Собираем чистый объект сообщения
                    lastMessageDto = new MessageDto(realId, sender, subject, text, time, false,
                            serverUid, status, messageType, filePath,
                            isGroup != 0);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении последнего входящего сообщения для Push", e);
            e.printStackTrace();
        }

        return lastMessageDto;
    }

    public boolean updateMessageTypeAndPath(int messageId, String type, String path) {
        if (type == null || type.trim().isEmpty()) {
            Log.w("MailLis_DB_MediaUpdate", "updateMessageTypeAndPath: отмена, передан пустой тип.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isUpdated = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому потоку ретрая или IMAP менять пути к файлам, не мешая плавному чтению UI
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            values.put("message_type", type.trim());
            values.put("file_path", path != null ? path.trim() : null);

            // 2. ИСПРАВЛЕНИЕ: Контролируем количество измененных строк на диске
            int affectedRows = db.update(
                    "messages",
                    values,
                    "id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(messageId)}
            );

            // Если строка физически обновилась на диске смартфона — фиксируем транзакцию
            if (affectedRows > 0) {
                db.setTransactionSuccessful(); // Намертво записываем изменения в лог WAL
                isUpdated = true;
                Log.d("MailLis_DB_MediaUpdate", "🎉 Успешно обновлен тип контента (" + type + ") и путь к файлу для сообщения ID: " + messageId);
            } else {
                Log.w("MailLis_DB_MediaUpdate", "Предупреждение: сообщение с ID " + messageId + " не найдено. Данные медиа не изменены.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_MediaUpdate", "Критическая ошибка транзакции при обновлении медиа-параметров для ID: " + messageId, e);
            e.printStackTrace();
            isUpdated = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул многопоточности WAL
            db.endTransaction();
        }

        return isUpdated;
    }

    public long createGroupChat(String groupName, byte[] generatedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long localId = -1;

        // Генерируем уникальный сквозной глобальный ID группы на базе таймстемпа!
        // Число вида 1793948574932 никогда не совпадет с локальными автоинкрементами 1, 2, 3...
        long globalUniqueId = System.currentTimeMillis();

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает пишущий поток от конфликтов с фоновой службой синхронизации SyncRunnable
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: СБОРКА ДАННЫХ ДЛЯ ТАБЛИЦЫ GROUP_CHATS
            ContentValues values = new ContentValues();
            values.put("group_name", groupName != null ? groupName.trim() : "Новая группа");
            values.put("creator_email", creatorEmail != null ? creatorEmail.trim().toLowerCase() : "unknown@mail.ru");
            values.put("group_secret_key", generatedGroupKey);

            // Сразу записываем сгенерированный глобальный ID, общий для всей P2P-сети!
            values.put("remote_group_id", globalUniqueId);

            // Вставляем запись в таблицу групповых чатов
            localId = db.insert("group_chats", null, values);

            // ШАГ 2: КОНТРОЛЬ РЕЗУЛЬТАТА И ЗАВЕРШЕНИЕ ТРАНЗАКЦИИ
            if (localId != -1) {
                // Коммитим транзакцию на физический накопитель только при успешном INSERT
                db.setTransactionSuccessful();
                Log.i("MailLis_DB_Group", "🎉 Группа \"" + groupName + "\" успешно создана в транзакции.");
                Log.d("MailLis_DB_Group", "Локальный ID: " + localId + " | Глобальный P2P ID (remote_group_id): " + globalUniqueId);
            } else {
                globalUniqueId = -1;
                Log.w("MailLis_DB_Group", "Ошибка insert: база данных вернула -1 при создании группы " + groupName);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка транзакции при создании группы: " + groupName, e);
            e.printStackTrace();
            globalUniqueId = -1;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return globalUniqueId;
    }

    public boolean isGroupChat(long chatId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean isGroup = false;

        // ИСПРАВЛЕНИЕ КОЛЛИЗИИ: Мы полностью убираем проверку "id = CAST(? AS INTEGER)",
        // потому что локальный автоинкремент (1, 2, 3...) всегда совпадает с ID одиночных чатов.
        // Мы проверяем ТОЛЬКО remote_group_id, который является уникальным глобальным номером группы!
        String sql = "SELECT 1 FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1";

        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                isGroup = true;
            }
        } catch (Exception e) {
            android.util.Log.e("MailLis_DB", "Ошибка при проверке типа чата для ID: " + chatId, e);
            e.printStackTrace();
        }

        return isGroup;
    }

    public boolean saveReceivedGroupChat(long remoteChatId, String incomingGroupName,
                                         byte[] decryptedGroupKey, String creatorEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean isSuccess = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому конвейеру дописывать крипто-ключи, не блокируя секундный таймер интерфейса
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: СБОРКА ДАННЫХ ДЛЯ ТАБЛИЦЫ GROUP_CHATS
            ContentValues values = new ContentValues();

            // Записываем глобальный remote_group_id, общий для всей P2P-сети
            values.put("remote_group_id", remoteChatId);
            values.put("group_name", incomingGroupName != null ? incomingGroupName.trim() : "Групповой чат");
            values.put("creator_email", creatorEmail != null ? creatorEmail.trim().toLowerCase() : "unknown@mail.ru");

            // Ключ записываем только если он успешно расшифрован на этапе финального инвайта
            if (decryptedGroupKey != null && decryptedGroupKey.length > 0) {
                values.put("group_secret_key", decryptedGroupKey);
            }

            // ШАГ 2: АТОМАРНЫЙ ИМПОРТ С ПЕРЕЗАПИСЬЮ ПРИ КОНФЛИКТАХ
            // Так как на колонку remote_group_id установлено ограничение UNIQUE, SQLite
            // автоматически перезапишет старую заготовку чата свежим AES-ключом .
            long rowId = db.insertWithOnConflict(
                    "group_chats",
                    null,
                    values,
                    CONFLICT_REPLACE
            );

            if (rowId != -1) {
                // Коммитим транзакцию на физический диск только при успешной записи
                db.setTransactionSuccessful();
                isSuccess = true;
                Log.i("MailLis_DB_Group", "🎉 Группа успешно импортирована/обновлена в транзакции. Локальный ID: "
                        + rowId + " (Remote ID: " + remoteChatId + ")");
            } else {
                Log.w("MailLis_DB_Group", "Ошибка CONFLICT_REPLACE: база данных вернула -1 для группы ID: " + remoteChatId);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка транзакции при сохранении полученного чата. Remote ID: " + remoteChatId, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, высвобождая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public boolean savePublicKey(String email, byte[] publicKeyBytes) {
        if (email == null || email.trim().isEmpty() || publicKeyBytes == null || publicKeyBytes.length == 0) {
            Log.w("MailLis_DB_CryptoCache", "savePublicKey: отмена, переданы пустые или невалидные параметры.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean isSuccess = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому конвейеру сохранять ключи, не блокируя секундный таймер UI-потока
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: СБОРКА ДАННЫХ ДЛЯ ТАБЛИЦЫ PUBLIC_KEYS
            ContentValues values = new ContentValues();
            values.put("email", normalizedEmail);
            values.put("peer_public_key", publicKeyBytes);

            // ШАГ 2: АТОМАРНАЯ ЗАПИСЬ С ОБНОВЛЕНИЕМ ПРИ КОНФЛИКТАХ (CONFLICT_REPLACE)
            // Если на колонку email установлено ограничение UNIQUE/PRIMARY KEY, СУБД
            // автоматически обновит старый ключ свежим бинарным массивом .
            long rowId = db.insertWithOnConflict(
                    "public_keys",
                    null,
                    values,
                    CONFLICT_REPLACE
            );

            if (rowId != -1) {
                // Коммитим транзакцию на физический накопитель только при успешной записи
                db.setTransactionSuccessful();
                isSuccess = true;
                Log.d("MailLis_DB_CryptoCache", "🔒 Ключ для " + normalizedEmail + " успешно сохранен в кэш public_keys. RowID: " + rowId);
            } else {
                Log.w("MailLis_DB_CryptoCache", "Ошибка CONFLICT_REPLACE: база данных вернула -1 для email: " + normalizedEmail);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_CryptoCache", "Критическая ошибка транзакции при сохранении публичного ключа для: " + normalizedEmail, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 3. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public byte[] getPublicKeyByEmail(String email) {
        if (email == null) {
            return null;
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;
        String sql = "SELECT peer_public_key FROM public_keys WHERE email LIKE ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{email.trim().toLowerCase()})) {
            if (cursor != null && cursor.moveToFirst()) {
                key = cursor.getBlob(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return key;
    }

    public boolean addGroupMember(long remoteGroupId, String userEmail) {
        if (userEmail == null || userEmail.trim().isEmpty()) {
            Log.w("MailLis_DB_Members", "addGroupMember: отмена, передан пустой userEmail.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = userEmail.trim().toLowerCase();
        boolean isSuccess = false;

        // 1. ОТКРЫВАЕМ БЕЗОПАСНУЮ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Позволяет фоновому конвейеру писать состав группы, не блокируя секундный таймер UI-потока
        db.beginTransactionNonExclusive();
        try {
            // ШАГ 1: НАХОДИМ РЕАЛЬНЫЙ ЛОКАЛЬНЫЙ ID ГРУППЫ
            long localGroupId = -1;
            String findIdSql = "SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";

            try (Cursor cursor = db.rawQuery(findIdSql, new String[]{String.valueOf(remoteGroupId), String.valueOf(remoteGroupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localGroupId = cursor.getLong(0);
                }
            }

            // Если группа не найдена (например, инвайт еще не успел создаться), выходим с откатом транзакции
            if (localGroupId == -1) {
                Log.e("MailLis_DB_Members", "Не удалось добавить мембера " + normalizedEmail + ": группа с ID " + remoteGroupId + " не найдена в group_chats!");
                db.setTransactionSuccessful(); // Фиксируем "пустой" успех, чтобы закрыть транзакцию без падения
                return false;
            }

            // ШАГ 2: ЗАЩИТА ОТ ДУБЛИКАТОВ (Повторный инвайт или веерный SMTP-пакет)
            String checkSql = "SELECT 1 FROM group_members WHERE group_id = CAST(? AS INTEGER) AND user_email LIKE ? LIMIT 1";
            try (Cursor cursor = db.rawQuery(checkSql, new String[]{String.valueOf(localGroupId), normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    db.setTransactionSuccessful(); // Закрываем транзакцию — юзер уже на месте, цель достигнута
                    Log.d("MailLis_DB_Members", "Пользователь " + normalizedEmail + " уже состоит в группе " + remoteGroupId);
                    return true;
                }
            }

            // ШАГ 3: СБОРКА ДАННЫХ И АТОМАРНАЯ ВСТАВКА В БАЗУ ДАННЫХ
            ContentValues values = new ContentValues();
            values.put("group_id", localGroupId); // Записываем вычисленный локальный ID (1, 2, 3...)
            values.put("user_email", normalizedEmail);

            long rowId = db.insert("group_members", null, values);

            if (rowId != -1) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на флеш-памяти
                isSuccess = true;
                Log.i("MailLis_DB_Members", "👥 В группу " + remoteGroupId + " (локальный ID: " + localGroupId + ") успешно добавлен: " + normalizedEmail);
            } else {
                Log.w("MailLis_DB_Members", "Ошибка insert: база данных вернула -1 при добавлении мембера " + normalizedEmail);
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Members", "Критическая ошибка транзакции при добавлении мембера: " + normalizedEmail, e);
            e.printStackTrace();
            isSuccess = false;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isSuccess;
    }

    public List<String> getGroupMembers(long groupId, String myEmail) {
        List<String> members = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ИСПРАВЛЕНИЕ: Мы связываем group_id в таблице участников с РЕАЛЬНЫМ локальным id
        // из таблицы group_chats. Мы находим этот локальный id по переданному groupId,
        // проверяя обе колонки (id и remote_group_id). Это самый чистый и быстрый SQL-путь!
        String sql = "SELECT user_email FROM group_members WHERE user_email != ? AND (" +
                "  group_id = CAST(? AS INTEGER) OR " +
                "  group_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                ")";

        String normalizedMyEmail = myEmail != null ? myEmail.trim().toLowerCase() : "";

        // Передаем аргументы: 1 раз для фильтрации email и 2 раза для условий сопоставления ID
        String[] selectionArgs = new String[]{
                normalizedMyEmail,
                String.valueOf(groupId),
                String.valueOf(groupId)
        };

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String memberEmail = cursor.getString(0);
                    if (memberEmail != null) {
                        members.add(memberEmail.trim().toLowerCase());
                    }
                } while (cursor.moveToNext());
            }
            Log.d("MailLis_DB", "Для группы (ID: " + groupId + ") успешно извлечено участников: " + members.size());
        } catch (Exception e) {
            Log.e("MailLis_DB", "Критическая ошибка при получении списка участников группы: " + groupId, e);
            e.printStackTrace();
        }

        return members;
    }

    public byte[] getGroupSecretKey(long groupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        byte[] key = null;

        // ИСПРАВЛЕНИЕ: Проверяем ОДНОВРЕМЕННО колонку id и колонку remote_group_id
        String sql = "SELECT group_secret_key FROM group_chats " +
                "WHERE id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER) " +
                "LIMIT 1";

        // Передаем groupId дважды — для каждого из условий OR
        String[] selectionArgs = new String[]{String.valueOf(groupId), String.valueOf(groupId)};

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            // Если SQLite нашел строку — извлекаем бинарный BLOB ключа
            if (cursor != null && cursor.moveToFirst()) {
                int keyIndex = cursor.getColumnIndex("group_secret_key");
                if (keyIndex >= 0) {
                    key = cursor.getBlob(keyIndex);
                } else {
                    // Фолбэк, если индекс не определился по имени
                    key = cursor.getBlob(0);
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при получении AES-ключа группы для ID: " + groupId, e);
            e.printStackTrace();
        }

        return key;
    }

    public boolean deletePublicKeyByEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            Log.w("MailLis_CryptoCache", "deletePublicKeyByEmail: отмена, передан пустой email.");
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean isDeleted = false;

        // 1. ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ
        // Защищает пишущий поток от коллизий с секундным таймером и фоновым сервисом IMAP/SMTP
        db.beginTransactionNonExclusive();
        try {
            // ВЫПОЛНЯЕМ ТОЧЕЧНОЕ УДАЛЕНИЕ СТРОКИ ИЗ ТАБЛИЦЫ PUBLIC_KEYS
            // Оператор LIKE совместно с нижним регистром защищает от скрытых пробелов
            int rowsAffected = db.delete(
                    "public_keys",
                    "email LIKE ?",
                    new String[]{normalizedEmail}
            );

            // Если из таблицы физически исчезла запись — коммитим транзакцию
            if (rowsAffected > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на флеш-памяти
                isDeleted = true;
                Log.d("MailLis_Crypto", "🎉 Успешно! deletePublicKeyByEmail: Ключ для " + normalizedEmail + " удален. Очищено строк: " + rowsAffected);
            } else {
                Log.w("MailLis_Crypto", "Предупреждение: ключ для " + normalizedEmail + " не найден в кэше public_keys.");
            }

        } catch (Exception e) {
            Log.e("MailLis_Crypto", "Критическая ошибка транзакции при удалении публичного ключа для: " + normalizedEmail, e);
            e.printStackTrace();
            isDeleted = false;
        } finally {
            // 2. ГАРАНТИРОВАННО закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return isDeleted;
    }

    public boolean deleteGroupChatAndMessages(long groupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;

        // ИСПОЛЬЗУЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ (Защита от SQLiteDatabaseLockedException)
        db.beginTransactionNonExclusive();
        try {
            // ПРЕДВАРИТЕЛЬНЫЙ ШАГ: Находим оба ID (локальный и глобальный) до удаления,
            // чтобы подзапросы не сбоили в процессе очистки таблиц!
            long localId = -1;
            long remoteId = -1;

            String findIdsSql = "SELECT id, remote_group_id FROM group_chats " +
                    "WHERE id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(findIdsSql, new String[]{String.valueOf(groupId), String.valueOf(groupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localId = cursor.getLong(0);
                    remoteId = cursor.getLong(1);
                }
            }

            // Если группа вообще не найдена в базе — тихо выходим, транзакция успешна (её и так нет)
            if (localId == -1) {
                db.setTransactionSuccessful();
                return true;
            }

            // ШАГ 1: УДАЛЯЕМ САМУ КОМНАТУ ИЗ group_chats (ПЕРЕНЕСЕНО НА ПЕРВОЕ МЕСТО)
            // Если в схеме БД есть ON DELETE CASCADE, этот шаг САМ очистит сообщения и мемберов!
            int deletedChats = db.delete(
                    "group_chats",
                    "id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localId), String.valueOf(remoteId)}
            );

            // ШАГ 2: ФОЛБЭК-ОЧИСТКА (Для баз данных, где забыли прописать FOREIGN KEY CASCADE)
            // Гарантированно дочищаем хвосты по сохраненным ID
            // Дочищаем участников
            db.delete(
                    "group_members",
                    "group_id = CAST(? AS INTEGER) OR group_id = CAST(? AS INTEGER)",
                    new String[]{String.valueOf(localId), String.valueOf(remoteId)}
            );

            // Дочищаем сообщения группы (is_group_message = 1)
            db.delete(
                    "messages",
                    "is_group_message = 1 AND (chat_id = CAST(? AS INTEGER) OR chat_id = CAST(? AS INTEGER))",
                    new String[]{String.valueOf(localId), String.valueOf(remoteId)}
            );

            // ШАГ 3: ФИНАЛИЗАЦИЯ И КОНТРОЛЬ
            if (deletedChats > 0) {
                db.setTransactionSuccessful(); // Намертво фиксируем изменения на флеш-памяти
                success = true;
                Log.i("MailLis_DB_Del", "🎉 Групповой чат " + groupId + " (Лок: " + localId + " | Дист: " + remoteId + ") полностью стерт.");
            } else {
                Log.w("MailLis_DB_Del", "Предупреждение: строка группы не была удалена из group_chats.");
            }

        } catch (Exception e) {
            Log.e("MailLis_DB_Del", "Критическая ошибка транзакции при удалении группы " + groupId, e);
            e.printStackTrace();
            success = false;
        } finally {
            // Гарантированно закрываем транзакцию, возвращая пишущий поток в пул WAL
            db.endTransaction();
        }

        return success;
    }

    public String getRecipientEmailByChatId(long chatId) {
        String recipientEmail = EMPTI_STRING;
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Используем CAST(? AS INTEGER) и LIMIT 1 для максимальной скорости запроса
        String sql = "SELECT recipient_email FROM chats WHERE id = CAST(? AS INTEGER) LIMIT 1";

        // Использование try-with-resources гарантирует автоматическое закрытие курсора
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(chatId)})) {

            // Проверяем, нашла ли база данных нужную строку
            if (cursor != null && cursor.moveToFirst()) {
                int emailIdx = cursor.getColumnIndex("recipient_email");
                if (emailIdx >= 0) {
                    String result = cursor.getString(emailIdx);
                    if (result != null) {
                        recipientEmail = result.trim().toLowerCase(); // Возвращаем стандартизированный email
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MailLis_DB", "Ошибка при поиске recipient_email по одиночному chatId: " + chatId, e);
            e.printStackTrace();
        }

        return recipientEmail;
    }

    public List<String> getGroupMembersEmails(long groupId, String myEmail) {
        List<String> membersList = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // СВЕРХБЫСТРЫЙ РЕЛЯЦИОННЫЙ ЗАПРОС:
        // Мы выбираем user_email из таблицы участников.
        // Условие в скобках автоматически связывает переданный groupId с локальным group_id,
        // проверяя как прямую привязку, так и глобальный remote_group_id через таблицу group_chats.
        String sql = "SELECT user_email FROM group_members WHERE user_email != ? AND (" +
                "  group_id = CAST(? AS INTEGER) OR " +
                "  group_id = (SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) LIMIT 1)" +
                ")";

        String normalizedMyEmail = myEmail != null ? myEmail.trim().toLowerCase() : "";

        // Передаем аргументы: 1 раз для фильтрации своего email и 2 раза для условий сопоставления ID
        String[] selectionArgs = new String[]{
                normalizedMyEmail,
                String.valueOf(groupId),
                String.valueOf(groupId)
        };

        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String memberEmail = cursor.getString(0);
                    if (memberEmail != null && !memberEmail.isEmpty()) {
                        // Добавляем очищенный стандартизированный email в список
                        membersList.add(memberEmail.trim().toLowerCase());
                    }
                } while (cursor.moveToNext());
            }
            Log.d("MailLis_DB_Members", "Для группы (ID: " + groupId + ") успешно извлечено участников: " + membersList.size());
        } catch (Exception e) {
            Log.e("MailLis_DB_Members", "Критическая ошибка при получении email участников группы: " + groupId, e);
            e.printStackTrace();
        }

        return membersList;
    }

    public boolean removeGroupMemberByEmail(long groupId, String userEmail) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean success = false;
        String normalizedEmail = userEmail.trim().toLowerCase();

        db.beginTransaction();
        try {
            // 1. Находим локальный ID группы (подстраховка от ID Mismatch)
            long localGroupId = -1;
            String findIdSql = "SELECT id FROM group_chats WHERE remote_group_id = CAST(? AS INTEGER) OR id = CAST(? AS INTEGER) LIMIT 1";
            try (Cursor cursor = db.rawQuery(findIdSql, new String[]{String.valueOf(groupId), String.valueOf(groupId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    localGroupId = cursor.getLong(0);
                }
            }

            if (localGroupId != -1) {
                // 2. Удаляем строку из линкера участников
                int rows = db.delete("group_members", "group_id = CAST(? AS INTEGER) AND user_email LIKE ?",
                        new String[]{String.valueOf(localGroupId), normalizedEmail});

                db.setTransactionSuccessful();
                success = rows > 0;
                Log.i("MailLis_DB_Sync", "Участник " + normalizedEmail + " успешно удален из группы " + groupId);
            }
        } catch (Exception e) {
            Log.e("MailLis_DB_Sync", "Ошибка удаления участника " + normalizedEmail, e);
        } finally {
            db.endTransaction();
        }
        return success;
    }

    public String getGroupNameByGroupId(long groupId) {
        String groupName = EMPTI_STRING; // Вы можете заменить на вашу константу EMPTI_STRING, если она есть
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // ОПТИМИЗАЦИЯ: Сканируем таблицу group_chats, используя CAST и лимит в 1 строку
        String sql = "SELECT group_name FROM group_chats " +
                "WHERE id = CAST(? AS INTEGER) OR remote_group_id = CAST(? AS INTEGER) " +
                "LIMIT 1";

        String[] selectionArgs = new String[]{String.valueOf(groupId), String.valueOf(groupId)};

        // Использование try-with-resources гарантирует 100% закрытие курсора и защиту от Memory Leaks
        try (Cursor cursor = db.rawQuery(sql, selectionArgs)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex("group_name");
                if (nameIdx >= 0) {
                    String result = cursor.getString(nameIdx);
                    if (result != null) {
                        groupName = result.trim();
                    }
                }
            }
            Log.d("MailLis_DB_Group", "getGroupNameByGroupId для ID " + groupId + " -> " + groupName);
        } catch (Exception e) {
            Log.e("MailLis_DB_Group", "Критическая ошибка при поиске group_name для ID: " + groupId, e);
            e.printStackTrace();
        }

        return groupName;
    }

    public boolean cleanUpPublicKeyIfNoContexts(String email) {
        if (email == null || email.trim().isEmpty()) return false;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String normalizedEmail = email.trim().toLowerCase();
        boolean keyWasDeleted = false;

        // ОТКРЫВАЕМ НЕЭКСКЛЮЗИВНУЮ ТРАНЗАКЦИЮ (Защита WAL пула)
        db.beginTransactionNonExclusive();
        try {
            // МОЩНЫЙ СУММИРУЮЩИЙ ЗАПРОС (Reference Counting)
            // Считаем сумму совпадений в одиночных чатах И в составах всех групп!
            String sql =
                    "SELECT (" +
                            "  (SELECT COUNT(1) FROM chats WHERE recipient_email LIKE ?) + " +
                            "  (SELECT COUNT(1) FROM group_members WHERE user_email LIKE ?)" +
                            ") AS active_contexts_count";

            int activeContexts = 0;
            try (Cursor cursor = db.rawQuery(sql, new String[]{normalizedEmail, normalizedEmail})) {
                if (cursor != null && cursor.moveToFirst()) {
                    activeContexts = cursor.getInt(0);
                }
            }

            Log.d("MailLis_Crypto_Clean", "Пользователь " + normalizedEmail + " имеет активных контекстов: " + activeContexts);

            // ИДЕАЛЬНЫЙ МОМЕНТ: Связей больше нет, ключ официально становится "сиротой"
            if (activeContexts == 0) {
                // Стираем ключ из кэша, так как он больше не нужен для работы мессенджера
                int affectedRows = db.delete("public_keys", "email LIKE ?", new String[]{normalizedEmail});

                db.setTransactionSuccessful(); // Фиксируем изменения
                keyWasDeleted = (affectedRows > 0);
                Log.i("MailLis_Crypto_Clean", "🔒 Ключ для " + normalizedEmail + " успешно удален. Стерто строк кэша: " + affectedRows);
            } else {
                // Чаты еще есть, коммитим пустую транзакцию (просто закрываем её без отката)
                db.setTransactionSuccessful();
                Log.d("MailLis_Crypto_Clean", "Удаление ключа отменено: пользователь задействован в чатах.");
            }

        } catch (Exception e) {
            Log.e("MailLis_Crypto_Clean", "Ошибка при каскадной проверке ключа для " + normalizedEmail, e);
            e.printStackTrace();
        } finally {
            db.endTransaction(); // Гарантированно освобождаем поток
        }

        return keyWasDeleted;
    }
}
