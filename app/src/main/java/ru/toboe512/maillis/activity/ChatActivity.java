package ru.toboe512.maillis.activity;


import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.core.content.ContextCompat;
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
    private EditText editTextMessage;
    // Переменные для работы с базой данных
    private DBService dbService;
    private int chatId = 1; // ID текущего чата по умолчанию (для тестов)
    //клиент
    private EmailClient emailClient;
    private String recipientEmail = EMPTI_STRING;
    private String myEmail = EMPTI_STRING; // Наш реальный Email из настроек

    // 1. Объявляем переменную приемника на уровне класса ChatActivity
    private android.content.BroadcastReceiver messageUpdateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Загружаем наш собственный логин из SharedPreferences
        SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", MODE_PRIVATE);
        myEmail = prefs.getString("email_login", "my@mail.ru").trim();

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

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        recipientEmail = dbService.getRecipientEmailByChatId(chatId);
        messageList = new ArrayList<>();
        messagesAdapter = new MessagesAdapter(this, messageList, emailClient, dbService);

        var layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Чат строится снизу вверх, как в Telegram
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messagesAdapter);

        // 2. Инициализируем приемник сигналов от фоновой службы с поддержкой goAsync()
        messageUpdateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("MailLis_ChatUI", "Получен сигнал новой почты! Мгновенно обновляем переписку в чате...");

                // Включаем асинхронный режим, чтобы удержать контекст на время чтения диска
                final PendingResult pendingResult = goAsync();

                new Thread(() -> {
                    try {
                        // Используем наш отлаженный метод: считываем базу и точечно обновляем экран через DiffUtil
                        updateUiFromDatabase();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        // Сигнализируем системе о завершении фоновой задачи
                        pendingResult.finish();
                    }
                }).start();
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

                // 2. ИСПРАВЛЕНИЕ: Единый источник правды! Сразу перечитываем базу данных через наш DiffUtil.
                // Сообщение мгновенно появится на экране со значком "Часики" и БОЛЬШЕ НЕ БУДЕТ ДВОИТЬСЯ.
                updateUiFromDatabase();

                // Чистим поле ввода
                editTextMessage.getText().clear();

                String sendTxt;
                try {
                    byte[] sharedSecret = dbService.getSevedPublicKey(chatId);
                    sendTxt = sharedSecret != null ? emailClient.getCryptoService().encode(text, sharedSecret) : text;
                } catch (GeneralSecurityException | IOException e) {
                    throw new RuntimeException(e);
                }

                // 3. Отправляем сообщение по сети через SMTP
                emailClient.sendEmailOverNetwork(chatId, recipientEmail, MAILLIS_CHAT_MESSAGE, sendTxt,
                        generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {

                            @Override
                            public void onSynchronized(Message updatedMessages) {
                                // Успешно отправлено! Обновляем UI через DiffUtil
                                updateUiFromDatabase();
                            }

                            @Override
                            public void onError(Exception e) {
                                // Сеть моргнула или полностью отключилась
                                Toast.makeText(ChatActivity.this, "Сеть недоступна. Сообщение отложено.", Toast.LENGTH_SHORT).show();

                                // Перечитываем SQLite, чтобы у сообщения мгновенно загорелся значок "Часики" (is_sent = 0)
                                updateUiFromDatabase();

                                // ВЫЗОВ РЕТРАЯ: Принудительно ставим задачу проверки очереди ретрая в networkExecutor,
                                // чтобы как только соединение восстановится, письмо тут же улетело автоматически!
                                //emailClient.retryUnsentMessages(prefs);
                            }
                        });
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
        IntentFilter filter = new IntentFilter("ru.toboe512.maillis.UPDATE_CHATS");

        // Используем ContextCompat вместо стандартного registerReceiver.
        // Это полностью отключает проверку Lint и гарантирует защиту Android 14+ на уровне системы!
        ContextCompat.registerReceiver(
                this,
                messageUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED // Указываем, что интент строго внутренний
        );

        // Первичное обновление истории при заходе на экран (наш старый код)
        updateUiFromDatabase();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ;

        if (messageUpdateReceiver != null) {
            unregisterReceiver(messageUpdateReceiver);
        }
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
}