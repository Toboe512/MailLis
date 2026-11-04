package ru.toboe512.maillis.activity;

import static android.content.Intent.getIntent;

import static ru.toboe512.maillis.config.Config.PREFS_NAME;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
    private List<Contact> availableContacts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle Bundle) {
        super.onCreate(Bundle);

        // =========================================================================
        // УЛЬТИМАТИВНЫЙ ФИКС ЧАСОВ: ДЕЛАЕМ ШТОРКУ ПРОЗРАЧНОЙ И КАСКАДНОЙ
        // =========================================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();

            // 1. Сбрасываем любые полноэкранные флаги, которые могли принудительно гасить часы
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // 2. Включаем режим кастомной отрисовки фона под шторкой
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            // 3. Делаем саму полосу шторки полностью прозрачной (чтобы тулбар просвечивал сквозь неё)
            window.setStatusBarColor(Color.TRANSPARENT);

            // 4. Заставляем разметку заехать ПОД прозрачную шторку, но вернуть иконки часов
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decorView = window.getDecorView();
                // Флаг LAYOUT_STABLE стабилизирует часы, а LAYOUT_FULLSCREEN пускает цвет наверх
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

                // Жестко вырезаем флаг LIGHT_STATUS_BAR, заставляя часы намертво стать БЕЛЫМИ!
                decorView.setSystemUiVisibility(flags);
            }
        }

        setContentView(R.layout.activity_add_members); // Создайте простой ListView в XML

        groupId = getIntent().getLongExtra("GROUP_ID", -1);
        dbService = DBServiceSQLite.getInstance(this);
        emailClient = EmailClientImpl.getInstance(dbService, this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        listView = findViewById(R.id.listViewContactsToGroup);

        loadAvailableContacts();
    }

    private void loadAvailableContacts() {
        String myEmail = prefs.getString(Config.KEY_EMAIL_LOGIN, "");
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
        String myEmail = prefs.getString(Config.KEY_EMAIL_LOGIN, "");
        String groupName = dbService.getGroupNameByGroupId(groupId);

        // 1. Добавляем новичка в свою локальную базу данных
        dbService.addGroupMember(groupId, targetEmail);

        // 2. Запускаем фоновый SMTP-транспорт
        new Thread(() -> {
            try {
                // А. Шлем Пре-Инвайт новичку, чтобы его телефон создал заготовку группы
                emailClient.sendPreInviteOverNetwork(targetEmail, groupId, groupName, prefs);

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
