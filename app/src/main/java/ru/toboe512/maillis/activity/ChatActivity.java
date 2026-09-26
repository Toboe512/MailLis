package ru.toboe512.maillis.activity;


import static android.content.Context.MODE_PRIVATE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.UPDATE_INTERVAL_MS;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.MessagesAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.Message;

public class ChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private MessagesAdapter messagesAdapter;
    private List<Message> messageList = new ArrayList<>();
    private List<Message> freshlyLoadedMessages = new ArrayList<>();
    private EditText editTextMessage;
    // Переменные для работы с базой данных
    private DBService dbService;
    private int chatId = 1; // ID текущего чата по умолчанию (для тестов)
    //клиент
    private EmailClient emailClient;
    private String recipientEmail = EMPTI_STRING;
    private String myEmail = EMPTI_STRING; // Наш реальный Email из настроек

    // ТАЙМЕР ДЛЯ АВТООБНОВЛЕНИЯ ЧАТА
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Загружаем наш собственный логин из SharedPreferences
        SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", MODE_PRIVATE);
        String savedEmail = prefs.getString("email_login", "my@mail.ru");
        myEmail = savedEmail.trim();

        // Стрелочка назад
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Чат");
        }

        // Получаем ID чата и имя из интента (передано с главного экрана)
        Intent intent = getIntent();
        if (intent != null) {
            chatId = intent.getIntExtra("CHAT_ID", 1);
            String chatTitle = intent.getStringExtra("CHAT_TITLE");
            if (chatTitle != null && actionBar != null) {
                actionBar.setTitle(chatTitle);
            }
        }


        editTextMessage = findViewById(R.id.editTextMessage);
        recyclerView = findViewById(R.id.recyclerViewMessages);

        dbService = new DBServiceSQLite(this);
        emailClient = new EmailClientImpl(dbService, this);
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
                SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", MODE_PRIVATE);

                // 1. Запускаем опрос сети в фоновом потоке
                new Thread(() -> {
                    // Метод лезет в сеть и тихо сохраняет новые письма в SQLite
                    List<Message> messages = emailClient.fetchIncomingHandshakeOverNetwork(ChatActivity.this, prefs);
                    // 3. Переключаемся на главный поток для отрисовки
                    runOnUiThread(() -> updateUiFromDatabase());
                }).start();

                // Планируем следующий запуск через 20 секунд
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        // КРИТИЧЕСКИ ВАЖНО: Загружаем сохраненную историю сообщений из SQLite при открытии
        updateUiFromDatabase();

        ImageButton buttonSend = findViewById(R.id.buttonSendMessage);
        // Кнопка локальной отправки сообщения
        buttonSend.setOnClickListener(v ->
        {
            String text = editTextMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                long currentTimestamp = System.currentTimeMillis();

                // 1. Сохраняем сообщение локально в SQLite базу данных
                int generatedId = dbService.saveMessage(chatId, myEmail, MAILLIS_CHAT_MESSAGE, text, true, currentTimestamp);
                String senderEmail = myEmail;

                // 2. Отображаем сообщение на экране
                ru.toboe512.maillis.dto.Message message = new ru.toboe512.maillis.dto.Message(
                        generatedId,
                        senderEmail,
                        EMPTI_STRING,
                        text,
                        currentTimestamp,
                        true,
                        EMPTI_STRING
                );
                messageList.add(message);

                // Уведомляем адаптер, что добавился ОДИН элемент в конец (это экономит ресурсы)
                messagesAdapter.notifyItemInserted(messageList.size() - 1);

                recyclerView.scrollToPosition(messageList.size() - 1);
                String sendTxt;
                try {
                    byte[] sharedSecret = dbService.getSharedSecret(chatId);
                    sendTxt = sharedSecret != null ? emailClient.getCryptoService().encode(text, sharedSecret) : text;
                } catch (GeneralSecurityException | IOException e) {
                    throw new RuntimeException(e);
                }

                // 3. Отправляем сообщение по сети через SMTP
                emailClient.sendEmailOverNetwork(chatId, recipientEmail, MAILLIS_CHAT_MESSAGE, sendTxt,
                        generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {

                            @Override
                            public void onSynchronized(Message updatedMessages) {
                                // Код выполнится в Главном Потоке автоматически!
                                // Перечитываем обновленную историю из базы данных (где уже прописан server_uid)
                                // messageList.clear();
                                if (updatedMessages != null) {
                                    messageList.add(message);
                                }
                                //   messagesAdapter.notifyDataSetChanged();
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

        int id = item.getItemId();

        if (id == R.id.action_block_user) {
            showBlockUserDialog(); // Показываем окно бана
            return true;
        }

        if (id == R.id.action_delete_chat) {
            showDeleteChatDialog(); // Показываем окно удаления
            return true;
        }


        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 1. Сразу загружаем историю из локальной SQLite
        messageList.clear();
        messageList.addAll(dbService.getMessages(chatId, myEmail));// Перечитываем базу для синхронизации ID
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

    private void showBanUserDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Блокировка пользователя")
                .setMessage("Вы уверены, что хотите заблокировать " + recipientEmail + "? Вы больше не будете получать сообщения от него.")
                .setPositiveButton("Заблокировать", (dialog, which) -> {
                    new Thread(() -> {
                        // 1. Добавляем email в бан-лист
                        dbService.banUser(recipientEmail);

                        runOnUiThread(() -> {
                            Toast.makeText(this, "Пользователь заблокирован", Toast.LENGTH_SHORT).show();
                            finish(); // Закрываем экран чата и выходим назад
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        // Надуваем созданное XML-меню чата в Toolbar
        getMenuInflater().inflate(R.menu.menu_chat, menu);
        return true;
    }

    // --- ДИАЛОГ 1: БЛОКИРОВКА ПОЛЬЗОВАТЕЛЯ ---
    private void showBlockUserDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Блокировка")
                .setMessage("Вы уверены, что хотите заблокировать " + recipientEmail + "? Письма от него будут автоматически уничтожаться.")
                .setCancelable(true)
                .setPositiveButton("Заблокировать", (dialog, which) -> {
                    new Thread(() -> {
                        // 1. Вносим email в таблицу ban_list (метод, который мы написали ранее)
                        dbService.banUser(recipientEmail);

                        // 2. Логично также сразу удалить переписку с ним
                        dbService.deleteChatAndMessages(chatId);

                        runOnUiThread(() -> {
                            Toast.makeText(this, "Пользователь добавлен в чёрный список", Toast.LENGTH_SHORT).show();
                            finish(); // Закрываем экран чата и возвращаемся на главный экран
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // --- ДИАЛОГ 2: УДАЛЕНИЕ ЧАТА ---
    private void showDeleteChatDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Удаление чата")
                .setMessage("Вы действительно хотите удалить этот чат и всю историю сообщений? Отменить это действие будет нельзя.")
                .setCancelable(true)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    new Thread(() -> {
                        // Удаляем данные из SQLite
                        boolean isDeleted = dbService.deleteChatAndMessages(chatId);

                        runOnUiThread(() -> {
                            if (isDeleted) {
                                Toast.makeText(this, "Чат успешно удален", Toast.LENGTH_SHORT).show();
                                finish(); // Закрываем экран и возвращаемся в список чатов
                            } else {
                                Toast.makeText(this, "Не удалось удалить чат", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // Полностью оптимизированный метод синхронизации UI без notifyDataSetChanged
    private void updateUiFromDatabase() {
        runOnUiThread(() -> {
            List<Message> freshMessages = dbService.getMessages(chatId, myEmail);
            if (freshMessages != null && messagesAdapter != null) {

                // ИСПРАВЛЕНИЕ: Передаем список в адаптер с поддержкой DiffUtil
                messagesAdapter.setMessages(freshMessages);

                // Плавно скроллим к самому последнему сообщению, если список обновился
                if (!freshMessages.isEmpty()) {
                    recyclerView.scrollToPosition(freshMessages.size() - 1);
                }
            }
        });
    }

}