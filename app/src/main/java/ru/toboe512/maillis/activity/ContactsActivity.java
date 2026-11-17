package ru.toboe512.maillis.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ManageContactsAdapter;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.ContactDto;

public class ContactsActivity extends AppCompatActivity {

    private DBService dbService;
    private List<ContactDto> contactList;
    private ManageContactsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        // ИСПРАВЛЕНИЕ: Делаем системные часы и иконки статус-бара ТЁМНЫМИ и читаемыми
        // Получаем управление системными окнами (WindowInsetsController)
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true); // true = тёмные значки (для светлого фона), false = белые значки

        // Находим нашу кастомную стрелочку назад и вешаем на неё закрытие экрана
        ImageButton buttonChatBack = findViewById(R.id.buttonContactBack);
        if (buttonChatBack != null) {
            buttonChatBack.setOnClickListener(v -> finish());
        }

        // Инициализируем сервисы работы с базой
        dbService = DBServiceSQLite.getInstance(this);
        contactList = new ArrayList<>();

        // Настраиваем RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recyclerViewContactsList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Инициализируем адаптер с колбэком удаления
        adapter = new ManageContactsAdapter(
                contactList, // АРГУМЕНТ 1: Список данных
                // АРГУМЕНТ 2: Лямбда для кнопки удаления (красный крестик)
                (contact, position) ->
                        new AlertDialog.Builder(ContactsActivity.this)
                                .setTitle("Удаление контакта")
                                .setMessage("Вы действительно хотите удалить контакт " + contact.getEmail() + "?")
                                .setPositiveButton("Удалить", (dialog, which) ->
                                        new Thread(() -> {
                                            boolean isDeleted = dbService.deleteContact(contact.getEmail());
                                            runOnUiThread(() -> {
                                                if (isDeleted) {
                                                    contactList.remove(position);
                                                    adapter.notifyItemRemoved(position);
                                                    adapter.notifyItemRangeChanged(position, contactList.size());
                                                }
                                            });
                                        }).start())
                                .setNegativeButton("Отмена", null)
                                .show(),

                // АРГУМЕНТ 3: Лямбда для кнопки БАНА (серый замок).
                (contact, position) -> {
                    Log.d("MailLis_Click", "Ура, клик по кнопке бана сработал для: " + contact.getEmail());

                    new AlertDialog.Builder(ContactsActivity.this)
                            .setTitle("Блокировка контакта")
                            .setMessage("Вы уверены, что хотите заблокировать " + contact.getEmail() +
                                    "? Его сообщения будут автоматически уничтожаться, а сам он переместится в Чёрный список.")
                            .setCancelable(true)
                            .setPositiveButton("Заблокировать", (dialog, which) ->
                                    new Thread(() -> {
                                        // 1. Добавляем пользователя в таблицу ban_list в SQLite
                                        boolean isBanned = dbService.banUser(contact.getEmail());

                                        // 2. Сразу находим чат с ним по email и удаляем всю историю
                                        long associatedChatId = dbService.getChatIdByRecipientEmail(contact.getEmail());
                                        if (associatedChatId > 0) {
                                            dbService.deleteChatAndMessages(associatedChatId);
                                        }

                                        runOnUiThread(() -> {
                                            if (isBanned) {
                                                contactList.remove(position);
                                                adapter.notifyItemRemoved(position);
                                                adapter.notifyItemRangeChanged(position, contactList.size());
                                                Toast.makeText(ContactsActivity.this, "Контакт заблокирован", Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(ContactsActivity.this, "Ошибка при блокировке", Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }).start())
                            .setNegativeButton("Отмена", null)
                            .show();
                } // Конец третьего аргумента
        );

        recyclerView.setAdapter(adapter);

        // 4. Кнопка перехода на экран создания (AddContactActivity)
        Button buttonGoToAddContact = findViewById(R.id.buttonGoToAddContact);
        buttonGoToAddContact.setOnClickListener(v -> {
            Intent intent = new Intent(ContactsActivity.this, AddContactActivity.class);
            startActivity(intent);
        });
    }

    // Перечитываем список контактов каждый раз при возвращении на экран (например, после добавления нового)
    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            List<ContactDto> contacts = dbService.getContacts();
            runOnUiThread(() -> {
                contactList.clear();
                contactList.addAll(contacts);
                // КРИТИЧЕСКИЙ ФИКС: Сообщаем, что с самой первой позиции вставилась пачка элементов
                // Это мгновенно уберёт жёлтый ворнинг и заставит список обновиться плавно! [source]
                adapter.notifyItemRangeChanged(0, contacts.size());
            });
        }).start();
    }
}