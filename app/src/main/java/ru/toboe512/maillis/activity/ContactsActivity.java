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
        dbService = new DBServiceSQLite(new ChatDbHelper(this));
        contactList = new ArrayList<>();

        // 3. Настраиваем RecyclerView
        recyclerView = findViewById(R.id.recyclerViewContactsList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Инициализируем адаптер с колбэком удаления
        adapter = new ManageContactsAdapter(contactList, (contact, position) -> {
            // МЕТОД УДАЛЕНИЯ: Срабатывает при клике на крестик
            // Создаем диалоговое окно подтверждения
            new androidx.appcompat.app.AlertDialog.Builder(ContactsActivity.this)
                    .setTitle("Удаление контакта")
                    .setMessage("Вы действительно хотите удалить контакт " + contact.getEmail() + "?")
                    .setCancelable(true) // Позволяет закрыть окно, кликнув мимо него

                    // Если пользователь нажал "Да" — запускаем фоновый поток удаления
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        new Thread(() -> {
                            // Удаляем строку из SQLite
                            boolean isDeleted = dbService.deleteContact(contact.getEmail()) > 0;

                            runOnUiThread(() -> {
                                if (isDeleted) {
                                    contactList.remove(position);
                                    // Обновляем конкретную позицию с плавной анимацией
                                    adapter.notifyItemRemoved(position);
                                    // Уведомляем адаптер об изменении индексов оставшихся элементов
                                    adapter.notifyItemRangeChanged(position, contactList.size());

                                    Toast.makeText(ContactsActivity.this, "Контакт успешно удален", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(ContactsActivity.this, "Не удалось удалить контакт из базы", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }).start();
                    })

                    // Если пользователь нажал "Отмена" — просто закрываем окно, ничего не удаляя
                    .setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss())
                    .show(); // Принудительно отображаем диалог на экране
        });
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