package ru.toboe512.maillis.activity;


import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ChatsAdapter;
import ru.toboe512.maillis.adapters.MessagesAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Message;

public class ChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private MessagesAdapter messagesAdapter;
    private List<Message> messageList = new ArrayList<>();
    private EditText editTextMessage;
    private ImageButton buttonSend;

    // Переменные объявлены, но не использовались в onCreate
    private RecyclerView recyclerViewChats;
    private ChatsAdapter chatsAdapter;
    private List<ChatPreview> chatList = new ArrayList<>();

    // Переменные для работы с базой данных
    private DBService dBServiceSQLite;
    // Переменные для работы с базой данных
    private ChatDbHelper dbHelper;
    private int chatId = 1; // ID текущего чата по умолчанию (для тестов)

    //клиент
    EmailClient emailClient;

    private String recipientEmail = "";
    private String myEmail = ""; // Наш реальный Email из настроек

    // ТАЙМЕР ДЛЯ АВТООБНОВЛЕНИЯ ЧАТА
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    private static final int UPDATE_INTERVAL_MS = 20000; // Интервал опроса сервера — 20 секунд

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Загружаем наш собственный логин из SharedPreferences
        SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);
        String savedEmail = prefs.getString("email_login", "my@mail.ru");
        myEmail = (savedEmail != null) ? savedEmail.trim() : "my@mail.ru";

        // Стрелочка назад
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Чат");
        }

        editTextMessage = findViewById(R.id.editTextMessage);
        buttonSend = findViewById(R.id.buttonSendMessage);
        recyclerView = findViewById(R.id.recyclerViewMessages);

        // Получаем ID чата и имя из интента (передано с главного экрана)
        Intent intent = getIntent();
        if (intent != null) {
            chatId = intent.getIntExtra("CHAT_ID", 1);
            String chatTitle = intent.getStringExtra("CHAT_TITLE");
            if (chatTitle != null && actionBar != null) {
                actionBar.setTitle(chatTitle);
            }
        }

        dBServiceSQLite = new DBServiceSQLite(this);
        dbHelper = dBServiceSQLite.getDbHelper();
        emailClient = new EmailClientImpl(dbHelper, dBServiceSQLite);

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

            if (cursor != null && cursor.moveToFirst()) {
                int emailIdx = cursor.getColumnIndex("recipient_email");
                if (emailIdx >= 0) {
                    recipientEmail = cursor.getString(emailIdx);
                }
            }
        } catch (Exception e) {
            //TODO добавить проброс ошибки и логирование
            e.printStackTrace();
        }

        //TODO Убрать в релизе
        // Если контакт не найден, временно отправляем себе для теста
        if (recipientEmail == null || recipientEmail.isEmpty()) {
            String fallbackEmail = prefs.getString("email_login", myEmail);
            recipientEmail = (fallbackEmail != null) ? fallbackEmail : myEmail;
        }

        messageList = new ArrayList<>();
        messagesAdapter = new MessagesAdapter(this, messageList);

        var layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Чат строится снизу вверх, как в Telegram
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messagesAdapter);

        // Инициализируем задачу для таймера
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                // 1. Запускаем фоновую проверку новых писем
                var messages = emailClient.fetchIncomingEmailsOverNetwork(chatId, myEmail, prefs);

                if (!messages.isEmpty()) {
                    runOnUiThread(() -> messageList.addAll(dBServiceSQLite.loadMessagesFromDatabase(chatId, myEmail)));
                    messageList.addAll(dBServiceSQLite.loadMessagesFromDatabase(chatId, myEmail));// Перечитываем базу для синхронизации ID
                    //TODO проверить
                    // Перерисовываем список
                    messagesAdapter.notifyDataSetChanged();
                }

                // 2. Планируем следующий запуск
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        // КРИТИЧЕСКИ ВАЖНО: Загружаем сохраненную историю сообщений из SQLite при открытии
        messageList.clear();
        messageList.addAll(dBServiceSQLite.loadMessagesFromDatabase(chatId, myEmail));// Перечитываем базу для синхронизации ID
        // Перерисовываем список
        messagesAdapter.notifyDataSetChanged();

        // Кнопка локальной отправки сообщения
        buttonSend.setOnClickListener(v -> {
            String text = editTextMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                long currentTimestamp = System.currentTimeMillis();

                // 1. Сохраняем сообщение локально в SQLite базу данных
                int generatedId = dBServiceSQLite.saveMessageToDatabase(chatId, myEmail, text, currentTimestamp);
                String senderEmail = myEmail;

                // 2. Отображаем сообщение на экране
                ru.toboe512.maillis.dto.Message message = new ru.toboe512.maillis.dto.Message(
                        generatedId,
                        senderEmail,
                        text,
                        currentTimestamp,
                        true,
                        ""
                );
                messageList.add(message);

                messagesAdapter.notifyItemInserted(messageList.size() - 1);
                recyclerView.scrollToPosition(messageList.size() - 1);

                // 3. Отправляем сообщение по сети через SMTP
                messageList.addAll(emailClient.sendEmailOverNetwork(chatId, myEmail, recipientEmail, text, generatedId, prefs));
//TODO проверить
                // Перерисовываем список
                messagesAdapter.notifyDataSetChanged();

                // Корректная и чистая очистка поля ввода без лишних присвоений текста обратно
                editTextMessage.getText().clear();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 1. Сразу загружаем историю из локальной SQLite
        messageList.clear();
        messageList.addAll(dBServiceSQLite.loadMessagesFromDatabase(chatId, myEmail));// Перечитываем базу для синхронизации ID
        // Перерисовываем список
        messagesAdapter.notifyDataSetChanged();
        // 2. Включаем автоопрос почтового сервера Mail.ru
        updateHandler.post(updateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // КРИТИЧЕСКИ ВАЖНО: Выключаем таймер при уходе с экрана, чтобы не сажать батарею
        updateHandler.removeCallbacks(updateRunnable);
    }
}