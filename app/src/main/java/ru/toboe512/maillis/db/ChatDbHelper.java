package ru.toboe512.maillis.db;


import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ChatDbHelper extends SQLiteOpenHelper {

    // Имя файла базы данных на устройстве и версия
    private static final String DATABASE_NAME = "MailLisChat.db";
    private static final int DATABASE_VERSION = 5;

    // Конструктор
    public ChatDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    // Вызывается один раз при самом первом создании базы данных на телефоне
    @Override
    public void onCreate(SQLiteDatabase db) {
        // 1. Таблица контактов
        db.execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, email TEXT UNIQUE);");
        // 2. Таблица чатов (личные и групповые)
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, type TEXT, recipient_email TEXT, last_message TEXT, timestamp INTEGER, public_key BLOB);");

        // 3. Таблица сообщений (связана с таблицей чатов)
        // ОБНОВЛЕНО: Добавлена колонка server_uid с флагом UNIQUE
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER, " +
                "sender_email TEXT, " +
                "subject TEXT, " +
                "message_text TEXT, " +
                "timestamp INTEGER, " +
                "server_uid TEXT UNIQUE" + // Уникальный ID письма с сервера
                ");");
    }

    // Вызывается при обновлении версии приложения (если структура таблиц поменяется)
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS chats");
        db.execSQL("DROP TABLE IF EXISTS contacts");
        onCreate(db);
    }
}