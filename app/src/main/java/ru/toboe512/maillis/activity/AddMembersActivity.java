package ru.toboe512.maillis.activity;

import static android.content.Intent.getIntent;

import static ru.toboe512.maillis.config.Config.PREFS_NAME;
import static ru.toboe512.maillis.consts.Consts.EMPTI_STRING;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.client.EmailClientImpl;
import ru.toboe512.maillis.config.Config;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.Contact;

public class AddMembersActivity extends AppCompatActivity {

    private long groupId;
    private DBServiceSQLite dbService;
    private EmailClient emailClient;
    private SharedPreferences prefs;
    private ListView listView;
    private final List<Contact> availableContacts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_members);

        // Извлекаем ID группы с проверкой на "всеядность" типов данных (int / long)
        if (getIntent() != null) {
            // Пробуем считать как long (наш эталонный P2P ID на базе таймстемпа)
            groupId = getIntent().getLongExtra("GROUP_ID", -1);
        }

        if (groupId == -1) {
            Toast.makeText(this, "Ошибка: ID группы не передан", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Инициализация сервисов и UI
        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        listView = findViewById(R.id.listViewContactsToGroup);

        loadAvailableContacts();
    }

    private void loadAvailableContacts() {
        String myEmail = prefs.getString(Config.KEY_EMAIL_LOGIN, EMPTI_STRING);
        // Получаем все контакты устройства
        List<Contact> allContacts = dbService.getContacts();
        // Получаем тех, кто уже в группе, чтобы исключить их из списка
        List<String> existingMembers = dbService.getGroupMembersEmails(groupId, myEmail);

        availableContacts.clear();
        List<String> displayNames = new ArrayList<>();

        for (Contact contact : allContacts) {
            if (!existingMembers.contains(contact.getEmail()) && !contact.getEmail().equals(myEmail)) {
                availableContacts.add(contact);
                displayNames.add(contact.getName() + " (" + contact.getEmail() + ")");
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayNames);
        listView.setAdapter(adapter);

        // Обработка клика по контакту — приглашаем в группу!
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Contact selectedContact = availableContacts.get(position);
            inviteContactToExistingGroup(selectedContact.getEmail());
        });
    }

    private void inviteContactToExistingGroup(String targetEmail) {
        String myEmail = prefs.getString(Config.KEY_EMAIL_LOGIN, EMPTI_STRING);
        String groupName = dbService.getGroupNameByGroupId(groupId);

        // 1. Добавляем новичка в свою локальную базу данных
        dbService.addGroupMember(groupId, targetEmail);

        // 2. Запускаем фоновый SMTP-транспорт
        new Thread(() -> {
            try {
                byte[] groupSecretKey = dbService.getGroupSecretKey(groupId);
                // А. Шлем Пре-Инвайт новичку, чтобы его телефон создал заготовку группы
                emailClient.sendGroupInviteOverNetwork(targetEmail, groupId, groupName, groupSecretKey, prefs);

                // Б. Шлем уведомление всем ОСТАЛЬНЫМ участникам группы, чтобы они тоже добавили его Email к себе
                List<String> currentMembers = dbService.getGroupMembersEmails(groupId, myEmail);
                for (String oldMember : currentMembers) {
                    if (!oldMember.equals(targetEmail)) {
                        emailClient.sendGroupSyncNotification(oldMember, groupId, targetEmail, prefs);
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "Пользователь успешно приглашен в группу!", Toast.LENGTH_SHORT).show();
                    finish(); // Закрываем экран и возвращаемся в чат
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Ошибка при рассылке инвайтов", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
