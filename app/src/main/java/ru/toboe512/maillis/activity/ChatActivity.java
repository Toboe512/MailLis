package ru.toboe512.maillis.activity;


import static android.graphics.Bitmap.CompressFormat.JPEG;
import static android.util.Base64.NO_WRAP;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.stream.Collectors.toList;
import static ru.toboe512.maillis.config.Config.DEFAULT_SYNC_INTERVAL_MS;
import static ru.toboe512.maillis.config.Config.KEY_SYNC_INTERVAL;
import static ru.toboe512.maillis.config.Config.PREFS_NAME;
import static ru.toboe512.maillis.consts.Consts.CHAT_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.GROUP_IMAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.GROUP_LEAVE;
import static ru.toboe512.maillis.consts.Consts.GROUP_MESSAGE_MAIL;
import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;
import static ru.toboe512.maillis.consts.Consts.KEY_EMAIL_LOGIN;
import static ru.toboe512.maillis.consts.Consts.SPACE_STRING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.MessagesAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.MessageDto;

public class ChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private MessagesAdapter messagesAdapter;
    private EditText editTextMessage;
    // Переменные для работы с базой данных
    private DBService dbService;
    private long chatId = 1; // ID текущего чата по умолчанию (для тестов)
    private boolean isGroup; // НАШ НОВЫЙ СИСТЕМНЫЙ ФЛАГ ТИПА КОМНАТЫ
    private EmailClient emailClient;
    private String recipientEmail = EMPTI_STRING;
    private String myEmail = EMPTI_STRING; // Наш реальный Email из настроек
    // Объявляем переменную приемника на уровне класса ChatActivity
    private BroadcastReceiver messageUpdateReceiver;
    // Объявляем лаунчер как поле класса ChatActivity
    private ActivityResultLauncher<Intent> photoPickerLauncher;

    long actualIntervalMs;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    // Создаем явный, стабильный объект Runnable через лямбду Java 8
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            // А. Запускаем наше фоновое считывание статусов сообщений из SQLite
            updateUiFromDatabase();

            // Б. КРИТИЧЕСКИЙ ФИКС: Явно указываем timerRunnable для циклического перезапуска!
            // Теперь планировщик никогда не потеряет ссылку в памяти и цикл будет бесконечным.
            timerHandler.postDelayed(this, actualIntervalMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ИСПРАВЛЕНИЕ: Полностью выключаем системный ActionBar для этого экрана
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_chat);

        // Загружаем наш собственный логин из SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        myEmail = prefs.getString(KEY_EMAIL_LOGIN, EMPTI_STRING).trim();
        actualIntervalMs = prefs.getLong(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_MS);

        TextView textViewChatTitle = findViewById(R.id.textViewChatTitle);
        // Получаем ID чата и имя из интента (передано с главного экрана)
        Intent intent = getIntent();
        if (intent != null) {
            chatId = intent.getLongExtra("CHAT_ID", 1);
            String chatTitle = intent.getStringExtra("CHAT_TITLE");
            isGroup = getIntent().getBooleanExtra("IS_GROUP", false); // По умолчанию false (тет-а-тет)
            if (chatTitle != null) {
                textViewChatTitle.setText(chatTitle.trim());
            } else {
                // Подстраховка: если имя прилетело пустым, пишем дефолтное значение
                textViewChatTitle.setText(isGroup ? "Групповой чат" : "Личный чат");
            }
        }

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        recipientEmail = dbService.getRecipientEmailByChatId(chatId);
        messagesAdapter = new MessagesAdapter(this, emailClient, dbService);

        editTextMessage = findViewById(R.id.editTextMessage);
        recyclerView = findViewById(R.id.recyclerViewMessages);

        var layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Чат строится снизу вверх, как в Telegram
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messagesAdapter);

        // Инициализируем приемник сигналов от фоновой службы с поддержкой goAsync()
        messageUpdateReceiver = getMailLisChatUI();

        // КРИТИЧЕСКИ ВАЖНО: Загружаем сохраненную историю сообщений из SQLite при открытии
        updateUiFromDatabase();

        ImageButton buttonSend = findViewById(R.id.buttonSendMessage);

        // Кнопка локальной отправки сообщения
        buttonSend.setOnClickListener(v ->
        {
            String text = editTextMessage.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }

            long currentTimestamp = System.currentTimeMillis();
            // ПРОВЕРКА: Определяем архитектурный тип текущей комнаты чата
            boolean isGroup = dbService.isGroupChat(chatId);

            // 1. Сохраняем сообщение локально в SQLite базу данных
            // Метод saveMessage сам выставит статус "is_sent = 0" (часики) и "is_mine = 1"
            int generatedId;
            if (isGroup) {
                generatedId = dbService.saveGroupMessage(chatId, myEmail, GROUP_MESSAGE_MAIL + SPACE_STRING + chatId, text, currentTimestamp);
            } else {
                generatedId = dbService.saveMessage(chatId, myEmail, CHAT_MESSAGE, text, currentTimestamp);
            }

            // 2. Единый источник правды! Сразу перечитываем базу данных через наш DiffUtil.
            // Сообщение мгновенно появится на экране со значком "Часики" и больше не будет двоиться.
            updateUiFromDatabase();

            // Чистим поле ввода сообщения
            editTextMessage.getText().clear();

            if (isGroup) {
                // РЕЖИМ А: ОТПРАВКА В ГРУППОВОЙ ЧАТ (Симметричный AES-GCM)
                try {
                    // Достаем общий 32-байтный секретный ключ этой группы из SQLite
                    byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);

                    if (groupSecretKey != null && groupSecretKey.length > 0) {
                        // Шифруем текст сообщения общим AES-ключом группы
                        byte[] encryptedGroupBytes = CryptoServiceImpl.encryptGroupText(text, groupSecretKey);
                        String base64GroupText = Base64.encodeToString(encryptedGroupBytes, NO_WRAP);

                        // Вызываем спроектированный ранее веерный метод отправки SMTP всем участникам
                        emailClient.sendGroupMessageOverNetwork(chatId,
                                GROUP_MESSAGE_MAIL + SPACE_STRING + chatId, base64GroupText, generatedId, prefs);

                        // Сразу обновляем экран (сообщение перейдет в статус отправки)
                        updateUiFromDatabase();
                    } else {
                        Log.e("MailLis_Group", "Ошибка: Ключ группы еще не получен или поврежден!");
                        Toast.makeText(ChatActivity.this, "Ошибка: Ключ шифрования группы отсутствует.", LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e("MailLis_Group_Err", "Ошибка шифрования группового сообщения", e);
                    e.printStackTrace();
                }
                return;
            }
            // РЕЖИМ Б: ОТПРАВКА В ОДИНОЧНЫЙ ЧАТ (Асимметричный Google Tink ECIES)
            String sendTxt = text;
            try {
                // Извлекаем публичный Tink Protobuf-ключ собеседника
                byte[] peerPublicKeyBytes = dbService.getPublicKey(chatId);

                if (peerPublicKeyBytes != null && peerPublicKeyBytes.length > 5) {
                    // Передаем именно публичный ключ друга в метод encode с вашим контекстом "MailLis_P2P_Protocol_v1"
                    sendTxt = emailClient.getCryptoService().encrypt(text, peerPublicKeyBytes);
                } else {
                    Log.w("MailLis_Crypto", "Ключ чата еще не установлен. Отправка обычным текстом.");
                }
            } catch (GeneralSecurityException | IOException e) {
                Log.e("CRYPTO_SERVICE_ERR", "Ошибка асимметричного шифрования при отправке", e);
                e.printStackTrace();
                return;
            }

            // 3. Отправляем одиночное сообщение по сети через SMTP
            emailClient.sendEmailOverNetwork(chatId, recipientEmail, CHAT_MESSAGE, sendTxt,
                    generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {

                        @Override
                        public void onSynchronized(MessageDto updatedMessageDto) {
                            // Успешно отправлено! Обновляем UI через DiffUtil (появится одна галочка)
                            updateUiFromDatabase();
                        }

                        @Override
                        public void onError(Exception e) {
                            // Сеть полностью отключилась или моргнула
                            Toast.makeText(ChatActivity.this, "Сеть недоступна. Сообщение отложено.", LENGTH_SHORT).show();

                            // Перечитываем SQLite, чтобы у сообщения гарантированно горел значок "Часики" (is_sent = 0)
                            updateUiFromDatabase();
                        }
                    });
        });

        // ИНИЦИАЛИЗАЦИЯ ФОТО-ПИКЕРА ГАЛЕРЕИ
        photoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            // Запускаем обработку и отправку картинки в фоновом потоке
                            processAndSendPickedImage(selectedImageUri, prefs);
                        }
                    }
                }
        );

        // Находим кнопку скрепки и вешаем клик
        ImageButton buttonAttach = findViewById(R.id.buttonAttachFile);
        buttonAttach.setOnClickListener(v -> {
            // Запускаем современный системный Photo Picker (только изображения)
            Intent intentImage = new Intent(Intent.ACTION_PICK);
            intentImage.setType("image/*");
            photoPickerLauncher.launch(intentImage);
        });

        // Инициализируем нашу кастомную кнопку меню
        ImageButton buttonChatMenu = findViewById(R.id.buttonChatMenu);

        if (buttonChatMenu != null) {
            buttonChatMenu.setOnClickListener(v -> {
                // Создаем встроенное всплывающее меню, привязанное к кнопке "три точки"
                androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(ChatActivity.this, v);

                // Добавляем пункты меню программно (чтобы не плодить лишние XML файлы)

                popup.getMenu().add(0, 2, 1, "🗑️ Удалить чат");
                if (isGroup) {
                    popup.getMenu().add(0, 3, 2, "👤 Добавить участника");
                } else {
                    popup.getMenu().add(0, 1, 0, "🛑 ️Заблокировать");
                }
                // Настраиваем клики по пунктам меню
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        // ВЫЗЫВАЕМ ВАШУ ОТЛАЖЕННУЮ ЛОГИКУ УДАЛЕНИЯ ВСЕГО ЧАТА
                        showBlockUserDialog();
                        return true;
                    }
                    if (item.getItemId() == 2) {
                        // ВЫЗЫВАЕМ ВАШУ ОТЛАЖЕННУЮ ЛОГИКУ УДАЛЕНИЯ ВСЕГО ЧАТА
                        showDeleteChatDialog();
                        return true;
                    }
                    if (item.getItemId() == 3) {
                        // Запускаем наш новый экран выбора контактов, передавая ID текущей группы
                        Intent intent2 = new Intent(ChatActivity.this, AddMembersActivity.class);
                        intent2.putExtra("GROUP_ID", chatId);
                        startActivity(intent2);
                        return true;
                    }
                    return false;
                });

                // Показываем меню на экране
                popup.show();
            });
        }

        // 1. Инициализируем кнопки управления в тулбаре ChatActivity
        View layoutNormalToolbar = findViewById(R.id.layoutNormalToolbar); // Обычный заголовок чата
        View layoutEditToolbar = findViewById(R.id.layoutEditToolbar);     // Контекстный тулбар удаления
        TextView textViewSelectedCount = findViewById(R.id.textViewSelectedCount);
        ImageButton buttonDeleteSelected = findViewById(R.id.buttonDeleteSelected);
        ImageButton buttonCancelSelection = findViewById(R.id.buttonCancelSelection);

        // 2. Вешаем слушатель множественного выбора на адаптер
        messagesAdapter.setMultiChoiceListener(new MessagesAdapter.MultiChoiceListener() {
            @Override
            public void onMultiChoiceStateChanged(boolean isActive, int selectedCount) {
                if (isActive) {
                    // Переключаем тулбары: прячем обычный, показываем контекстный
                    if (layoutNormalToolbar != null) {
                        layoutNormalToolbar.setVisibility(View.GONE);
                    }
                    if (layoutEditToolbar != null) {
                        layoutEditToolbar.setVisibility(View.VISIBLE);
                    }
                    if (textViewSelectedCount != null) {
                        textViewSelectedCount.setText("Выбрано: " + selectedCount);
                    }
                    return;
                }
                // Возвращаем всё назад, если выбор пуст
                if (layoutNormalToolbar != null) {
                    layoutNormalToolbar.setVisibility(View.VISIBLE);
                }
                if (layoutEditToolbar != null) {
                    layoutEditToolbar.setVisibility(View.GONE);
                }
            }
        });

        // Кнопка отмены (крестик)
        if (buttonCancelSelection != null) {
            buttonCancelSelection.setOnClickListener(v -> messagesAdapter.clearSelection());
        }

        // 3. ОБРАБОТКА ЖЕСТКОГО УДАЛЕНИЯ ПАЧКИ ПИСЕМ 🗑️
        if (buttonDeleteSelected != null) {
            buttonDeleteSelected.setOnClickListener(v -> {
                List<MessageDto> toDelete = messagesAdapter.getSelectedMessages();
                if (toDelete.isEmpty()) {
                    return;
                }

                // Показываем нативное диалоговое окно подтверждения
                new androidx.appcompat.app.AlertDialog.Builder(ChatActivity.this)
                        .setTitle("Удаление сообщений")
                        .setMessage("Вы уверены, что хотите удалить эти сообщения (" + toDelete.size() + " шт.) со своего устройства?")
                        .setPositiveButton("Удалить", (dialog, which) -> {

                            // Уходим в рабочий поток SQLite, чтобы не фризить UI пачкой удалений
                            new Thread(() -> {
                                for (MessageDto msg : toDelete) {
                                    // Вызываем ваш стандартный метод удаления строки по ID сообщения
                                    dbService.deleteMessage(msg.getId());
                                }

                                // Возвращаемся на главный поток, сбрасываем режим выбора и перечитываем базу
                                runOnUiThread(() -> {
                                    messagesAdapter.clearSelection();
                                    updateUiFromDatabase(); // Наш DiffUtil плавно сотрет их с экрана!
                                    Toast.makeText(ChatActivity.this, "Сообщения успешно удалены", Toast.LENGTH_SHORT).show();
                                });
                            }).start();
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (messagesAdapter != null && !messagesAdapter.getSelectedMessages().isEmpty()) {
                    // Если включен режим множественного выбора — сбрасываем его
                    messagesAdapter.clearSelection();
                    return;
                }
                // Иначе — выключаем этот колбэк и закрываем экран чата
                setEnabled(false);
                finish();
            }
        });

        // Инициализируем нашу кастомную стрелочку назад
        ImageButton buttonChatBack = findViewById(R.id.buttonChatBack);


        if (buttonChatBack != null) {
            buttonChatBack.setOnClickListener(v -> {
                // Вызываем OnBackPressedDispatcher, который мы настроили ранее.
                // Это плавно закроет чат, предварительно сбросив режим удаления (если он был включен).
                getOnBackPressedDispatcher().onBackPressed();
            });
        }
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

        // Первичное обновление истории при заходе на экран (наш старый код)
        updateUiFromDatabase();

        // ЗАПУСКАЕМ БЕСКОНЕЧНЫЙ ЦИКЛ при входе пользователя в чат
        // Сначала на всякий случай очищаем очередь, чтобы избежать случайного размножения таймеров
        timerHandler.removeCallbacks(timerRunnable);

        // Запускаем первый мгновенный импульс
        timerHandler.post(timerRunnable);
        Log.d("MailLis_Timer", "🚀 Секундный цикл UI успешно запущен на главном потоке!");
    }

    @Override
    protected void onPause() {
        super.onPause();

        // ОСТАНАВЛИВАЕМ ЦИКЛ при выходе с экрана чата
        // Это критически важно, чтобы приложение не высаживало батарею смартфона в фоне!
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
            Log.w("MailLis_Timer", "🛑 Секундный цикл UI остановлен.");
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
        new AlertDialog.Builder(this)
                .setTitle("Блокировка")
                .setMessage("Вы уверены, что хотите заблокировать " + recipientEmail +
                        "? Письма от него будут автоматически уничтожаться.")
                .setCancelable(true)
                .setPositiveButton("Заблокировать", (dialog, which) -> {
                    new Thread(() -> {
                        // 1. Вносим email в таблицу ban_list (метод, который мы написали ранее)
                        dbService.banUser(recipientEmail);

                        // 2. Логично также сразу удалить переписку с ним
                        dbService.deleteChatAndMessages(chatId);

                        runOnUiThread(() -> {
                            Toast.makeText(this, "Пользователь добавлен в чёрный список", LENGTH_SHORT).show();
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

                        boolean isDeleted;
                        if (isGroup) {
                            SharedPreferences prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            long currentTimestamp = System.currentTimeMillis();
                            int generatedId = dbService.saveGroupMessage(chatId, myEmail,
                                    GROUP_LEAVE + SPACE_STRING + chatId, EMPTI_STRING, currentTimestamp);
                            emailClient.sendGroupMessageOverNetwork(chatId,
                                    GROUP_LEAVE + SPACE_STRING + chatId, EMPTI_STRING, generatedId, prefs);
                            isDeleted = dbService.deleteGroupChatAndMessages(chatId);
                        } else {
                            isDeleted = dbService.deleteChatAndMessages(chatId);
                        }
                        //TODO Придумать как удалять публичный ключ из БД
//                        if (isDeleted) {
//                            // 3. Если группа стерта, убираем из кэша публичные ключи участников
//                            for (String memberEmail : members) {
//                                dbService.deletePublicKeyByEmail(memberEmail);
//                            }
//                        }
                        runOnUiThread(() -> {
                            if (isDeleted) {
                                Toast.makeText(this, "Чат успешно удален", LENGTH_SHORT).show();
                                finish(); // Закрываем экран и возвращаемся в список чатов
                            } else {
                                Toast.makeText(this, "Не удалось удалить чат", LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Считывает свежую историю сообщений из SQLite и точечно обновляет галочки статуса на экране.
     * Абсолютно потокобезопасно.
     */
    private void updateUiFromDatabase() {
        // 1. Уходим в фоновый поток для быстрого чтения диска (чтобы не фризить UI)
        new Thread(() -> {
            try {
                // Вытаскиваем актуальный список сообщений из SQLite
                final List<MessageDto> freshMessageAllDtos = dbService.getAllMessages(chatId, isGroup);

                final List<MessageDto> freshMessageDtos = freshMessageAllDtos
                        .stream()
                        .filter((m) -> m.getSubject().contains(CHAT_MESSAGE) ||
                                m.getSubject().contains(CHAT_IMAGE_MAIL) ||
                                m.getSubject().contains(GROUP_MESSAGE_MAIL) ||
                                m.getSubject().contains(GROUP_IMAGE_MAIL)
                        )
                        .collect(toList());

                if (freshMessageDtos.isEmpty()) {
                    return;
                }
                // 2. ВОЗВРАЩАЕМСЯ НА ГЛАВНЫЙ ПОТОК ANDROID для перерисовки экрана!
                runOnUiThread(() -> {
                    if (messagesAdapter != null) {
                        // Передаем список в адаптер
                        messagesAdapter.setMessages(freshMessageDtos);
                    }
                    Log.d("MailLis_UI_Fix", "Интерфейс чата принудительно перерисован. Сообщений: "
                            + freshMessageDtos.size());

                });

            } catch (Exception e) {
                Log.e("MailLis_UI_Fix", "Ошибка при обновлении чата из базы данных", e);
                e.printStackTrace();
            }
        }).start();
    }

    //Инициализируем приемник сигналов от фоновой службы с поддержкой goAsync()
    @NonNull
    private BroadcastReceiver getMailLisChatUI() {
        return new BroadcastReceiver() {
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
    }

    /**
     * Извлекает Uri из галереи, сжимает картинку, сохраняет локально и передает в сетевой SMTP-слой.
     */
    private void processAndSendPickedImage(Uri imageUri, SharedPreferences prefs) {
        new Thread(() -> {
            try {

                byte[] rawImageBytes = getRawImageBytes(imageUri);
                if (rawImageBytes == null) {
                    return;
                }

                long currentTimestamp = System.currentTimeMillis();
                // 2. СОХРАНЯЕМ ЛОКАЛЬНУЮ КОПИЮ НА ДИСКЕ ТЕЛЕФОНА (ДЛЯ НАШЕГО ЭКРАНА ЧАТА)
                String localFileName = "sent_img_" + currentTimestamp + ".jpg";
                File localFile = new File(getCacheDir(), localFileName);
                try (var fos = new FileOutputStream(localFile)) {
                    fos.write(rawImageBytes);
                }

                // 3. ЗАПИСЫВАЕМ СТРОКУ В SQLITE БАЗУ ДАННЫХ
                // Передаем тип "IMAGE" и путь к нашему файлу. Статус изначально 0 (часики)
                int generatedId = dbService.saveGenericMessage(chatId, myEmail, CHAT_IMAGE_MAIL,
                        CHAT_IMAGE_MAIL, true, currentTimestamp);
                // Принудительно обновляем тип и путь в базе данных
                dbService.updateMessageTypeAndPath(generatedId, IMAGE_MSG, localFile.getAbsolutePath());

                // Мгновенно обновляем интерфейс через DiffUtil — картинка с часиками появится на экране!
                updateUiFromDatabase();

                // 4. ПЕРЕДАЕМ В СЕТЕВОЙ СЛОЙ НА ШИФРОВАНИЕ И ОТПРАВКУ ПО SMTP
                if (isGroup) {
                    emailClient.sendGroupImageOverNetwork(chatId, rawImageBytes, generatedId, prefs);
                } else {
                    emailClient.sendImageOverNetwork(chatId, recipientEmail, rawImageBytes, generatedId, prefs,
                            new EmailClientImpl.OnMessagesSynchronizedListener() {
                                @Override
                                public void onSynchronized(MessageDto updatedMessageDto) {
                                    // Успех! Сетевой слой сам вызвал markMessageAsSent, нам нужно только перерисовать галочку
                                    updateUiFromDatabase();
                                }

                                @Override
                                public void onError(Exception e) {
                                    runOnUiThread(() ->
                                            Toast.makeText(ChatActivity.this,
                                                            "Ошибка отправки медиафайла: " + e.getMessage(), LENGTH_SHORT)
                                                    .show());
                                    updateUiFromDatabase();
                                }
                            });
                }

            } catch (Exception e) {
                Log.e("MailLis_Media", "Ошибка подготовки фотографии к отправке", e);
                e.printStackTrace();
            }
        }).start();
    }

    @Nullable
    private byte[] getRawImageBytes(Uri imageUri) throws IOException {
        // 1. СЖАТИЕ КАРТИНКИ (Оптимизация трафика)
        InputStream inputStream = getContentResolver().openInputStream(imageUri);
        Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
        if (inputStream != null) {
            inputStream.close();
        }

        if (originalBitmap == null) {
            return null;
        }

        // Изменяем размер до разумного HD-предела (макс 1280px по большей стороне)
        int maxSize = 1280;
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        if (width > maxSize || height > maxSize) {
            float bitmapRatio = (float) width / (float) height;
            if (bitmapRatio > 1) {
                width = maxSize;
                height = (int) (width / bitmapRatio);
            } else {
                height = maxSize;
                width = (int) (height * bitmapRatio);
            }
            originalBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true);
        }

        // Конвертируем Bitmap в сжатый массив байт JPEG (качество 80%)
        var baos = new ByteArrayOutputStream();
        originalBitmap.compress(JPEG, 80, baos);
        byte[] rawImageBytes = baos.toByteArray();
        baos.close();
        return rawImageBytes;
    }
}