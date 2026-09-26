package ru.toboe512.maillis.activity;


import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ChatsAdapter;
import ru.toboe512.maillis.adapters.MessagesAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ChatPreview;
import ru.toboe512.maillis.dto.Message;

public class ChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private MessagesAdapter messagesAdapter;
    private List<Message> messageList = new ArrayList<>();

    private List<Message> freshlyLoadedMessages = new ArrayList<>();
    private EditText editTextMessage;
    private ImageButton buttonSend;

    // Переменные объявлены, но не использовались в onCreate
    private RecyclerView recyclerViewChats;
    private ChatsAdapter chatsAdapter;
    private List<ChatPreview> chatList = new ArrayList<>();

    // Переменные для работы с базой данных
    private DBService dbService;
    // Переменные для работы с базой данных
    private ChatDbHelper dbHelper;
    private int chatId = 1; // ID текущего чата по умолчанию (для тестов)

    //клиент
    private EmailClient emailClient;

    private String recipientEmail = "";
    private String myEmail = ""; // Наш реальный Email из настроек

    // ТАЙМЕР ДЛЯ АВТООБНОВЛЕНИЯ ЧАТА
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    private CryptoService cryptoService;

    private static final int UPDATE_INTERVAL_MS = 3000; // Интервал опроса сервера — 20 секунд

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

        dbHelper = new ChatDbHelper(this);
        dbService = new DBServiceSQLite(dbHelper);
        cryptoService = new CryptoServiceImpl(ChatActivity.this);
        emailClient = new EmailClientImpl(dbHelper, dbService, cryptoService);

        recipientEmail = dbService.getRecipientEmailByChatId(chatId);

        messageList = new ArrayList<>();
        messagesAdapter = new MessagesAdapter(this, messageList, emailClient, dbService);

        var layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Чат строится снизу вверх, как в Telegram
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messagesAdapter);

        // Инициализируем задачу для таймера
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);

                // 1. Запускаем опрос сети в фоновом потоке
                new Thread(() -> {
                    try {

                        // Метод лезет в сеть и тихо сохраняет новые письма в SQLite
                        List<Message> messages = emailClient.fetchIncomingHandshakeOverNetwork(ChatActivity.this, prefs);

                        // Мы ВСЕГДА проверяем локальную базу данных после завершения сети!
                        freshlyLoadedMessages = dbService.getMessages(chatId, myEmail)
                                .stream()
                                .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE))
                                .collect(Collectors.toList());

                        // 3. Переключаемся на главный поток для отрисовки
                        runOnUiThread(() -> {
                            if (freshlyLoadedMessages != null) {
                                // Проверяем, изменилось ли количество сообщений, чтобы не перерисовывать экран впустую
                                if (freshlyLoadedMessages.size() != messageList.size()) {
                                    messageList.clear();
                                    messageList.addAll(freshlyLoadedMessages);
                                    messagesAdapter.notifyDataSetChanged();
                                    scrollToBottomIfNeeded(); // Плавно скроллим вниз к новому письму

                                    android.util.Log.d("MailLis_Live", "Обнаружены новые сообщения! Экран перерисован.");
                                }
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                // Планируем следующий запуск через 20 секунд
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        // КРИТИЧЕСКИ ВАЖНО: Загружаем сохраненную историю сообщений из SQLite при открытии
        messageList.clear();
        messageList.addAll(dbService.getMessages(chatId, myEmail).stream()
                .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE))
                .collect(Collectors.toList()));// Перечитываем базу для синхронизации ID
        // Перерисовываем список
        messagesAdapter.notifyDataSetChanged();

        // Кнопка локальной отправки сообщения
        buttonSend.setOnClickListener(v ->
        {
            String text = editTextMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                long currentTimestamp = System.currentTimeMillis();

                // 1. Сохраняем сообщение локально в SQLite базу данных
                int generatedId = dbService.saveMessage(chatId, myEmail, MAILLIS_CHAT_MESSAGE, text, currentTimestamp);
                String senderEmail = myEmail;

                // 2. Отображаем сообщение на экране
                ru.toboe512.maillis.dto.Message message = new ru.toboe512.maillis.dto.Message(
                        generatedId,
                        senderEmail,
                        "",
                        text,
                        currentTimestamp,
                        true,
                        ""
                );
                messageList.add(message);

                // Уведомляем адаптер, что добавился ОДИН элемент в конец (это экономит ресурсы)
                messagesAdapter.notifyItemInserted(messageList.size() - 1);

                recyclerView.scrollToPosition(messageList.size() - 1);
                String sendTxt = "";
                try {
                    byte[] sharedSecret = dbService.getSharedSecret(chatId);
                    sendTxt = sharedSecret != null ? cryptoService.encode(text, sharedSecret) : text;
                } catch (GeneralSecurityException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 3. Отправляем сообщение по сети через SMTP
                emailClient.sendEmailOverNetwork(chatId, recipientEmail, MAILLIS_CHAT_MESSAGE, sendTxt,
                        generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {

                            @Override
                            public void onSynchronized(List<Message> updatedMessages) {
                                // Код выполнится в Главном Потоке автоматически!
                                // Перечитываем обновленную историю из базы данных (где уже прописан server_uid)
                                messageList.clear();
                                List<Message> messages = updatedMessages.stream()
                                        .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE))
                                        .collect(Collectors.toList());
                                messageList.addAll(messages);
                                messagesAdapter.notifyDataSetChanged();
                                scrollToBottomIfNeeded(); // плавно скроллим вниз
                            }

                            @Override
                            public void onError(Exception e) {
                                // Если сеть пропала или пароль неверный
                                Toast.makeText(ChatActivity.this, "Ошибка отправки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
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
        messageList.addAll(dbService.getMessages(chatId, myEmail).stream()
                .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE))
                .collect(Collectors.toList()));// Перечитываем базу для синхронизации ID
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

    private void scrollToBottom() {
        if (messageList != null && !messageList.isEmpty()) {
            // smoothScrollToPosition делает красивую плавную анимацию прокрутки
            recyclerView.smoothScrollToPosition(messageList.size() - 1);
        }
    }

    private void scrollToBottomIfNeeded() {
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager != null && !messageList.isEmpty()) {
            int lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition();

            // Если до конца чата осталось не больше 2 сообщений ИЛИ это наше собственное сообщение, то скроллим
            if (lastVisibleItemPosition >= messageList.size() - 3) {
                recyclerView.smoothScrollToPosition(messageList.size() - 1);
            }
        }
    }

}