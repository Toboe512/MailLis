package ru.toboe512.maillis.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.ManageContactsAdapter;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBServiceSQLite;
import ru.toboe512.maillis.dto.Contact;

public class ContactsActivity extends AppCompatActivity {

    private DBServiceSQLite dbService;
    private List<Contact> contactList;
    private ManageContactsAdapter adapter;
    private RecyclerView recyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        // 1. Настраиваем Toolbar и кнопку "Назад"
        Toolbar toolbar = findViewById(R.id.toolbarContacts);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Помним наш надежный хак: вешаем прямой клик на стрелочку навигации
        toolbar.setNavigationOnClickListener(v -> finish());

        // 2. Инициализируем сервисы работы с базой
        dbService = new DBServiceSQLite(this);
        contactList = new ArrayList<>();

        // 3. Настраиваем RecyclerView
        recyclerView = findViewById(R.id.recyclerViewContactsList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Инициализируем адаптер с колбэком удаления
        adapter = new ManageContactsAdapter(
                contactList, // АРГУМЕНТ 1: Список данных

                // АРГУМЕНТ 2: Лямбда для кнопки удаления (красный крестик)
                (contact, position) -> {
                    new androidx.appcompat.app.AlertDialog.Builder(ContactsActivity.this)
                            .setTitle("Удаление контакта")
                            .setMessage("Вы действительно хотите удалить контакт " + contact.getEmail() + "?")
                            .setPositiveButton("Удалить", (dialog, which) -> {
                                new Thread(() -> {
                                    boolean isDeleted = dbService.deleteContact(contact.getEmail()) > 0;
                                    runOnUiThread(() -> {
                                        if (isDeleted) {
                                            contactList.remove(position);
                                            adapter.notifyItemRemoved(position);
                                            adapter.notifyItemRangeChanged(position, contactList.size());
                                        }
                                    });
                                }).start();
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                },

                // АРГУМЕНТ 3: Лямбда для кнопки БАНА (серый замок). ПРОВЕРЬТЕ НАЛИЧИЕ ЭТОГО БЛОКА!
                (contact, position) -> {
                    android.util.Log.d("MailLis_Click", "Ура, клик по кнопке бана сработал для: " + contact.getEmail());

                    new androidx.appcompat.app.AlertDialog.Builder(ContactsActivity.this)
                            .setTitle("Блокировка контакта")
                            .setMessage("Вы уверены, что хотите заблокировать " + contact.getEmail() + "? Его сообщения будут автоматически уничтожаться, а сам он переместится в Чёрный список.")
                            .setCancelable(true)
                            .setPositiveButton("Заблокировать", (dialog, which) -> {
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
                                }).start();
                            })
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
            List<Contact> freshContacts = dbService.getContacts();
            runOnUiThread(() -> {
                if (freshContacts != null) {
                    contactList.clear();
                    contactList.addAll(freshFreshContacts(freshContacts));
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    // Вспомогательный метод фильтрации null на случай пустой базы
    private List<Contact> freshFreshContacts(List<Contact> list) {
        return list != null ? list : new ArrayList<>();
    }
}