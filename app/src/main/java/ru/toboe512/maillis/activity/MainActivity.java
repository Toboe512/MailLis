package ru.toboe512.maillis.activity;

import static ru.toboe512.maillis.consts.Consts.UPDATE_INTERVAL_MS;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ChatsAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.crypto.CryptoService;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ChatPreview;


public class MainActivity extends AppCompatActivity { // Важно: наследуемся от AppCompatActivity

    private RecyclerView recyclerViewChats;
    private ChatsAdapter chatsAdapter;
    private List<ChatPreview> chatList;
    private DBService dbService;

    //клиент
    private EmailClient emailClient;
    private Runnable updateRunnable;

    private final Handler updateHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dbService = new DBServiceSQLite(this);
        chatList = new ArrayList<>();

        chatsAdapter = new ChatsAdapter(this, chatList);
        emailClient = new EmailClientImpl(dbService, this);

        // Настраиваем RecyclerView для списка чатов
        recyclerViewChats = findViewById(R.id.recyclerViewChats);
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewChats.setAdapter(chatsAdapter); // Привязываем готовый адаптер

        // Инициализируем задачу для таймера
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                // Загружаем наш собственный логин из SharedPreferences
                SharedPreferences prefs = getSharedPreferences("MailLis_Prefs", Context.MODE_PRIVATE);

                // Запускаем сетевую операцию в отдельном фоновом потоке!
                new Thread(() -> {
                    try {
                        // Опрашиваем сервер в фоне — интерфейс теперь полностью свободен
                        var messages = emailClient.fetchIncomingHandshakeOverNetwork(MainActivity.this, prefs);
                        emailClient.retryUnsentMessages(prefs);
                        if (!messages.isEmpty()) {
                            // Если во время рукопожатия база обновилась, обновляем UI список чатов на главном потоке
                            new Handler(Looper.getMainLooper()).post(() -> {
                                refreshChatsList();
                            });
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                // Планируем следующий запуск через 20 секунд
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

// Нижняя круглая кнопка "+" теперь открывает создание группового чата, как и должна
        View fabNewChat = findViewById(R.id.fabNewChat);
        fabNewChat.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CreateChatActivity.class);
            startActivity(intent);
        });

    }

    // Вынесли обновление списка в отдельный удобный метод
    private void refreshChatsList() {
        if (dbService != null) {
            chatList.clear();

            chatList.addAll(dbService.loadChats()
                    .stream()
                    .peek((m) -> {
                        if (m.isHandShake()) {
                            m.setLastMessage("Чат создан");
                        }
                    })
                    .collect(Collectors.toList()));
            if (chatsAdapter != null) {
                chatsAdapter.notifyDataSetChanged();
            }
        }
    }

    // КРИТИЧЕСКИ ВАЖНО: Останавливаем таймер, когда пользователь сворачивает экран или уходит в настройки!
    @Override
    protected void onPause() {
        super.onPause();
        // Выключаем опрос сервера, чтобы он не плодился в памяти и не тратил батарею в фоне
        updateHandler.removeCallbacks(updateRunnable);
    }

    // Каждый раз, когда пользователь возвращается на этот экран, обновляем список чатов
    @Override
    protected void onResume() {
        super.onResume();
        chatList.clear();
        chatList.addAll(dbService.loadChats().stream()
                .peek((m) -> {
                    if (m.isHandShake()) {
                        m.setLastMessage("Чат создан");
                    }
                })
                .collect(Collectors.toList()));
        // Уведомляем адаптер о необходимости перерисовки
        if (chatsAdapter != null) {
            chatsAdapter.notifyDataSetChanged();
        }
        // 2. Включаем автоопрос почтового сервера Mail.ru
        updateHandler.post(updateRunnable);
    }

    // ОС Android сама вызовет этот метод для отрисовки меню в AppCompat-панели
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    // ОС Android сама вызовет этот метод для обработки кликов по кнопкам в панели

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();


        if (id == R.id.action_add_contact) {
            Toast.makeText(this, "Открываем добавление контакта", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, AddContactActivity.class);
            startActivity(intent);
            return true;
        }

        if (id == R.id.action_contacts) {
            // Открываем экран со списком контактов
            Intent intent = new Intent(MainActivity.this, ContactsActivity.class);
            startActivity(intent);
            return true;
        }

        // 2. ИСПРАВЛЕНИЕ: Переход на экран Чёрного списка (бан-листа)
        if (id == R.id.action_ban_list) {
            Intent intent = new Intent(MainActivity.this, ru.toboe512.maillis.activity.BanListActivity.class);
            startActivity(intent);
            return true;
        }

        // Ваши старые обработки (настройки и т.д.)
        if (id == R.id.action_settings) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}