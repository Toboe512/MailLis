package ru.toboe512.maillis.activity;

import static ru.toboe512.maillis.consts.Consts.PREFS_NAME;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ContactsAdapter;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.crypto.CryptoServiceImpl;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.Contact;

public class CreateChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ContactsAdapter adapter;
    private List<Contact> contactList;
    private DBService dbService;
    private EditText editTextGroupName;
    private EmailClient emailClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_chat);

        // 1. Настраиваем Toolbar и кнопку "Назад"
        Toolbar toolbar = findViewById(R.id.toolbarCreateChat);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        //   Помним наш надежный хак: вешаем прямой клик на стрелочку навигации
        toolbar.setNavigationOnClickListener(v -> finish());

        dbService = DBServiceSQLite.getInstance(this);
        contactList = new ArrayList<>();

        // Стрелочка назад
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Чат");
        }

        // Настраиваем список RecyclerView
        editTextGroupName = findViewById(R.id.editTextGroupName);
        recyclerView = findViewById(R.id.recyclerViewSelectContacts);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));


        // Загружаем данные из SQLite
        contactList = dbService.getContacts();

        adapter = new ContactsAdapter(this, contactList);

        // КРИТИЧЕСКИ ВАЖНО: Принудительно заставляем список на экране перерисовать полученные строки
        adapter.notifyDataSetChanged();

        // Диагностика: если база пуста, вежливо сообщаем об этом
        if (contactList.isEmpty()) {
            Toast.makeText(this, "Список контактов пока пуст. Добавьте кого-нибудь через '+'.", Toast.LENGTH_LONG).show();
        }

        recyclerView.setAdapter(adapter);

        emailClient = EmailClientImpl.getInstance(dbService, this);


        Button buttonCreateContact = findViewById(R.id.buttonViewContactTitle);
        buttonCreateContact.setOnClickListener(v -> {
            Intent intent = new Intent(CreateChatActivity.this, AddContactActivity.class);
            startActivity(intent);
            finish();
        });

        Button buttonCreateGroup = findViewById(R.id.buttonCreateGroup);
        buttonCreateGroup.setOnClickListener(v -> {
            String groupName = editTextGroupName.getText().toString().trim();

            // Проверяем, ввел ли пользователь имя чата
            if (groupName.isEmpty()) {
                Toast.makeText(CreateChatActivity.this, "Введите название группы!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Считаем, сколько человек выбрано галочками
            List<String> selectedEmails = new ArrayList<>();
            for (Contact c : contactList) {
                if (c.isSelected()) {
                    selectedEmails.add(c.getEmail());
                }
            }

            if (selectedEmails.isEmpty()) {
                Toast.makeText(CreateChatActivity.this, "Выберите хотя бы одного участника!", Toast.LENGTH_SHORT).show();
                return;
            }
            // Загружаем наш собственный логин из SharedPreferences
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            // РЕЖИМ 1: ОДИНОЧНЫЙ ЧАТ (Выбран ровно 1 человек и поле имени группы пустое)
            if (selectedEmails.size() == 1) {
                String senderEmail = selectedEmails.get(0);

                long newChatId = dbService.saveChat(groupName, senderEmail);// Сохраняем созданный чат в базу данных SQLite
                emailClient.sendHandshakeRequest(senderEmail, CreateChatActivity.this, newChatId, prefs);

                if (newChatId != -1) {
                    Toast.makeText(CreateChatActivity.this, "Чат успешно создан!", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(CreateChatActivity.this, "Ошибка создания чата", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            // РЕЖИМ 2: ГРУППОВОЙ ЧАТ (Выбрано несколько человек ИЛИ заполнено имя группы)
            final String finalGroupName = groupName;

            // Уходим в фоновый поток для тяжелых крипто-операций генерации и веерной рассылки
            new Thread(() -> {
                try {
                    // А. Генерируем уникальный 32-байтный секретный ключ AES-256 для этой группы
                    byte[] groupSecretKey = CryptoServiceImpl.generateGroupSymmetricKey();

                    String myEmail = prefs.getString("email_login", "my@mail.ru").trim();
                    // Б. Создаем групповую комнату в SQLite (is_group = 1, пишем BLOB ключа)
                    long newChatId = dbService.createGroupChat(finalGroupName, groupSecretKey, myEmail);

                    if (newChatId != -1) {
                        // В. В цикле привязываем участников к группе и высылаем им ключи в Tink-конвертах
                        for (String memberEmail : selectedEmails) {
                            dbService.addGroupMember(newChatId, memberEmail);

                            // Веерный SMTP: шлем инвайт с зашифрованным ключом
                            emailClient.sendGroupInviteOverNetwork(memberEmail, newChatId, finalGroupName, groupSecretKey, prefs);
                        }

                        // Возвращаемся в UI поток для закрытия экрана
                        runOnUiThread(() -> {
                            Toast.makeText(CreateChatActivity.this, "Группа \"" + finalGroupName + "\" создана!", Toast.LENGTH_SHORT).show();
                            //sendUpdateBroadcast();
                            finish();
                        });
                    } else {
                        runOnUiThread(() -> Toast.makeText(CreateChatActivity.this, "Ошибка записи группы в БД", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(CreateChatActivity.this, "Сбой криптографии: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
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

}
