package ru.toboe512.maillis.activity;


import static android.graphics.Bitmap.CompressFormat.JPEG;
import static androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED;
import static java.util.stream.Collectors.toList;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;
import static ru.toboe512.maillis.consts.Consts.IMAGE_MSG;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_IMAGE;
import static ru.toboe512.maillis.consts.Consts.MAILLIS_CHAT_MESSAGE;
import static ru.toboe512.maillis.consts.Consts.PREFS_NAME;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
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
    private List<MessageDto> messageDtoList = new ArrayList<>();
    private EditText editTextMessage;
    // Переменные для работы с базой данных
    private DBService dbService;
    private int chatId = 1; // ID текущего чата по умолчанию (для тестов)
    private boolean isGroup; // НАШ НОВЫЙ СИСТЕМНЫЙ ФЛАГ ТИПА КОМНАТЫ

    //клиент
    private EmailClient emailClient;
    private String recipientEmail = EMPTI_STRING;
    private String myEmail = EMPTI_STRING; // Наш реальный Email из настроек

    // 1. Объявляем переменную приемника на уровне класса ChatActivity
    private BroadcastReceiver messageUpdateReceiver;

    // Объявляем лаунчер как поле класса ChatActivity
    private ActivityResultLauncher<Intent> photoPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Загружаем наш собственный логин из SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
            isGroup = getIntent().getBooleanExtra("IS_GROUP", false); // По умолчанию false (тет-а-тет)
            if (chatTitle != null && actionBar != null) {
                actionBar.setTitle(chatTitle);
            }
        }

        editTextMessage = findViewById(R.id.editTextMessage);
        recyclerView = findViewById(R.id.recyclerViewMessages);

        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        recipientEmail = dbService.getRecipientEmailByChatId(chatId);
        messageDtoList = new ArrayList<>();
        messagesAdapter = new MessagesAdapter(this, messageDtoList, emailClient, dbService);

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

            // 1. Сохраняем сообщение локально в SQLite базу данных
            // Метод saveMessage сам выставит статус "is_sent = 0" (часики) и "is_mine = 1"
            int generatedId = dbService.saveMessage(chatId, myEmail, MAILLIS_CHAT_MESSAGE, text, currentTimestamp);

            // 2. Единый источник правды! Сразу перечитываем базу данных через наш DiffUtil.
            // Сообщение мгновенно появится на экране со значком "Часики" и больше не будет двоиться.
            updateUiFromDatabase();

            // Чистим поле ввода сообщения
            editTextMessage.getText().clear();

            // ПРОВЕРКА: Определяем архитектурный тип текущей комнаты чата
            boolean isGroup = dbService.isGroupChat(chatId);

            if (isGroup) {
                // РЕЖИМ А: ОТПРАВКА В ГРУППОВОЙ ЧАТ (Симметричный AES-GCM)
                try {
                    // Достаем общий 32-байтный секретный ключ этой группы из SQLite
                    byte[] groupSecretKey = dbService.getGroupSecretKey(chatId);

                    if (groupSecretKey != null && groupSecretKey.length > 0) {
                        // Шифруем текст сообщения общим AES-ключом группы
                        byte[] encryptedGroupBytes = CryptoServiceImpl.encryptGroupText(text, groupSecretKey);
                        String base64GroupText = Base64.encodeToString(encryptedGroupBytes, Base64.NO_WRAP);

                        // Вызываем спроектированный ранее веерный метод отправки SMTP всем участникам
                        emailClient.sendGroupMessageOverNetwork(chatId, text, base64GroupText, generatedId, prefs);

                        // Сразу обновляем экран (сообщение перейдет в статус отправки)
                        updateUiFromDatabase();
                    } else {
                        Log.e("MailLis_Group", "Ошибка: Ключ группы еще не получен или поврежден!");
                        Toast.makeText(ChatActivity.this, "Ошибка: Ключ шифрования группы отсутствует.", Toast.LENGTH_SHORT).show();
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
            emailClient.sendEmailOverNetwork(chatId, recipientEmail, MAILLIS_CHAT_MESSAGE, sendTxt,
                    generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {

                        @Override
                        public void onSynchronized(MessageDto updatedMessageDto) {
                            // Успешно отправлено! Обновляем UI через DiffUtil (появится одна галочка)
                            updateUiFromDatabase();
                        }

                        @Override
                        public void onError(Exception e) {
                            // Сеть полностью отключилась или моргнула
                            Toast.makeText(ChatActivity.this, "Сеть недоступна. Сообщение отложено.", Toast.LENGTH_SHORT).show();

                            // Перечитываем SQLite, чтобы у сообщения гарантированно горел значок "Часики" (is_sent = 0)
                            updateUiFromDatabase();

                            // ВЫЗОВ РЕТРАЯ: Принудительно пинаем наш общий networkExecutor,
                            // чтобы при восстановлении интернета сообщение улетело само!
                            // emailClient.retryUnsentMessages(prefs);
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

                        List<String> members = new ArrayList<>();

                        boolean isDeleted;
                        if (isGroup) {
                            //Вытаскиваем email участников, чтобы зачистить их ключи из public_keys
                            members = dbService.getGroupMembers(chatId, myEmail);
                            isDeleted = dbService.deleteGroupChatAndMessages(chatId);
                        } else {
                            // Удаляем данные из SQLite
                            isDeleted = dbService.deleteChatAndMessages(chatId);
                            members.add(dbService.getRecipientEmailByChatId(chatId));
                        }
                        if (isDeleted) {
                            // 3. Если группа стерта, убираем из кэша публичные ключи участников
                            for (String memberEmail : members) {
                                dbService.deletePublicKeyByEmail(memberEmail);
                            }
                        }
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

    /**
     * Считывает свежую историю сообщений из SQLite и точечно обновляет галочки статуса на экране.
     * Абсолютно потокобезопасно.
     */
    private void updateUiFromDatabase() {
        // 1. Уходим в фоновый поток для быстрого чтения диска (чтобы не фризить UI)
        new Thread(() -> {
            try {
                // Вытаскиваем актуальный список сообщений из SQLite
                final List<MessageDto> freshMessageDtos = dbService.getAllMessages(chatId, isGroup)
                        .stream()
                        .filter((m) -> m.getSubject().contains(MAILLIS_CHAT_MESSAGE) || m.getSubject().contains(MAILLIS_CHAT_IMAGE))
                        .collect(toList());

                if (freshMessageDtos.isEmpty()) {
                    return;
                }
                // 2. ВОЗВРАЩАЕМСЯ НА ГЛАВНЫЙ ПОТОК ANDROID для перерисовки экрана!
                runOnUiThread(() -> {
                    if (messagesAdapter != null) {

                        // Передаем список в наш адаптер с поддержкой DiffUtil
                        messagesAdapter.setMessages(freshMessageDtos);

                        // ХАК ДЛЯ ГАРАНТИРОВАННОГО ОБНОВЛЕНИЯ: Принудительно заставляем
                        // RecyclerView обновить отображение последнего элемента (наших галочек)
                        if (!freshMessageDtos.isEmpty()) {
                            int lastPosition = freshMessageDtos.size() - 1;
                            messagesAdapter.notifyItemChanged(lastPosition); // Жестко перерисовать статус!

                            // Плавно доскролливаем вниз к нашему сообщению
                            recyclerView.scrollToPosition(lastPosition);
                        }

                        Log.d("MailLis_UI_Fix", "Интерфейс чата принудительно перерисован. Сообщений: " + freshMessageDtos.size());
                    }
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
                long currentTimestamp = System.currentTimeMillis();

                // 1. СЖАТИЕ КАРТИНКИ (Оптимизация трафика)
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
                if (inputStream != null) {
                    inputStream.close();
                }

                if (originalBitmap == null) {
                    return;
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

                // 2. СОХРАНЯЕМ ЛОКАЛЬНУЮ КОПИЮ НА ДИСКЕ ТЕЛЕФОНА (ДЛЯ НАШЕГО ЭКРАНА ЧАТА)
                String localFileName = "sent_img_" + currentTimestamp + ".jpg";
                File localFile = new File(getCacheDir(), localFileName);
                try (var fos = new FileOutputStream(localFile)) {
                    fos.write(rawImageBytes);
                }

                // 3. ЗАПИСЫВАЕМ СТРОКУ В SQLITE БАЗУ ДАННЫХ
                // Передаем тип "IMAGE" и путь к нашему файлу. Статус изначально 0 (часики)
                int generatedId = dbService.saveGenericMessage(chatId, myEmail, MAILLIS_CHAT_IMAGE, MAILLIS_CHAT_IMAGE, true, currentTimestamp);
                // Принудительно обновляем тип и путь в базе данных
                dbService.updateMessageTypeAndPath(generatedId, IMAGE_MSG, localFile.getAbsolutePath());

                // Мгновенно обновляем интерфейс через DiffUtil — картинка с часиками появится на экране!
                updateUiFromDatabase();

                // 4. ПЕРЕДАЕМ В СЕТЕВОЙ СЛОЙ НА ШИФРОВАНИЕ И ОТПРАВКУ ПО SMTP
                // Используем метод, который мы спроектировали на прошлом шаге
                emailClient.sendImageOverNetwork(chatId, recipientEmail, rawImageBytes, generatedId, prefs, new EmailClientImpl.OnMessagesSynchronizedListener() {
                    @Override
                    public void onSynchronized(MessageDto updatedMessageDto) {
                        // Успех! Сетевой слой сам вызвал markMessageAsSent, нам нужно только перерисовать галочку
                        updateUiFromDatabase();

                        // Пинаем MainActivity, чтобы обновилось превью чата
//                        Intent updateChatsIntent = new Intent("ru.toboe512.maillis.UPDATE_CHATS");
//                        updateChatsIntent.setPackage(getPackageName());
//                        sendBroadcast(updateChatsIntent);
                    }

                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Ошибка отправки медиафайла: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        updateUiFromDatabase();
                    }
                });

            } catch (Exception e) {
                Log.e("MailLis_Media", "Ошибка подготовки фотографии к отправке", e);
                e.printStackTrace();
            }
        }).start();
    }
}