package ru.toboe512.maillis.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ChatsAdapter;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ChatPreview;


public class MainActivity extends AppCompatActivity { // Важно: наследуемся от AppCompatActivity

    private ChatsAdapter chatsAdapter;
    private DBService dbService;
    private BroadcastReceiver chatUpdateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        List<ChatPreview> chatList = new ArrayList<>();

        dbService = DBServiceSQLite.getInstance(this);
        chatsAdapter = new ChatsAdapter(this, chatList, dbService);

        // Пропишите это в onCreate() вашей MainActivity для проверки и запроса прав
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        setContentView(R.layout.activity_main);

        // Настраиваем RecyclerView для списка чатов
        RecyclerView recyclerViewChats = findViewById(R.id.recyclerViewChats);
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewChats.setAdapter(chatsAdapter); // Привязываем готовый адаптер

        // 2. ИСПРАВЛЕНИЕ: Переписываем инициализацию приемника с поддержкой асинхронности goAsync()
        chatUpdateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("MailLis_UI", "Получен сигнал от фоновой службы! Запускаем безопасное обновление...");

                // Переводим BroadcastReceiver в асинхронный фоновый режим работы
                final PendingResult pendingResult = goAsync();

                // Запускаем чтение базы в фоновом потоке
                new Thread(() -> {
                    try {
                        // Считываем свежие данные чатов из SQLite
                        final List<ChatPreview> freshChats = dbService.loadChats();

                        // Возвращаемся в главный поток для плавной отрисовки UI
                        runOnUiThread(() -> {
                            if (freshChats != null && chatsAdapter != null) {
                                // Наш DiffUtil точечно и красиво выведет новый чат на экран
                                chatsAdapter.updateChatsList(freshChats);
                                Log.d("MailLis_UI", "Список чатов на экране успешно обновлен в реальном времени!");
                            }
                        });
                    } catch (Exception e) {
                        Log.e("MailLis_UI", "Ошибка при фоновом обновлении UI из Broadcast", e);
                        e.printStackTrace();
                    } finally {
                        // КРИТИЧЕСКИ ВАЖНО: Обязательно сигнализируем системе, что фоновая задача завершена,
                        // и теперь ресивер можно безопасно удалить из памяти Android!
                        pendingResult.finish();
                    }
                }).start();
            }
        };

        // Нижняя круглая кнопка "+" теперь открывает создание группового чата, как и должна
        View fabNewChat = findViewById(R.id.fabNewChat);
        fabNewChat.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CreateChatActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Переносим запуск службы сюда — когда окно приложения гарантированно находится в фокусе!
        Intent serviceIntent = new Intent(this, ru.toboe512.maillis.service.MailLisPushService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.d("MailLis_Main", "Команда startForegroundService отправлена из метода onStart()");
    }

    @Override
    protected void onPause() {
        super.onPause();

        // ОБЯЗАТЕЛЬНО отвязываем приемник при выходе с экрана, чтобы избежать утечки памяти Android
        if (chatUpdateReceiver != null) {
            unregisterReceiver(chatUpdateReceiver);
        }
    }

    // Каждый раз, когда пользователь возвращается на этот экран, обновляем список чатов
    @Override
    protected void onResume() {
        super.onResume();

        var filter = new IntentFilter("ru.toboe512.maillis.UPDATE_CHATS");
        // Используем ContextCompat вместо стандартного registerReceiver.
        // Это полностью отключает проверку Lint и гарантирует защиту Android 14+ на уровне системы!
        ContextCompat.registerReceiver(
                this,
                chatUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED // Указываем, что интент строго внутренний
        );
        // Фоновое обновление списков чатов из SQLite через DiffUtil
        refreshChatsList();
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

        // ПЕРЕХОД НА ЭКРАН НАСТРОЕК ПРОФИЛЯ
        if (id == R.id.action_profile) {
            Intent intent = new Intent(MainActivity.this, ProfileActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // Фоновое обновление списков чатов из SQLite через DiffUtil
    private void refreshChatsList() {
        new Thread(() -> {
            List<ChatPreview> freshChats = dbService.loadChats();
            runOnUiThread(() -> {
                if (freshChats != null && chatsAdapter != null) {
                    chatsAdapter.updateChatsList(freshChats);
                }
            });
        }).start();
    }
}