package ru.toboe512.maillis.activity;

import static android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
import static android.widget.Toast.LENGTH_SHORT;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.adapters.BanListAdapter;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.db.DBServiceSQLite;

public class BanListActivity extends AppCompatActivity {

    private DBService dbService;
    private List<String> bannedEmailsList;
    private BanListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ban_list);

        // ИСПРАВЛЕНИЕ: Делаем системные часы и иконки статус-бара ТЁМНЫМИ и читаемыми
        // Получаем управление системными окнами (WindowInsetsController)
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true); // true = тёмные значки (для светлого фона), false = белые значки

        // 1. Настройка Toolbar
        Toolbar toolbar = findViewById(R.id.toolbarBanList);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        dbService = DBServiceSQLite.getInstance(this);
        bannedEmailsList = new ArrayList<>();

        // 2. Настройка списка
        RecyclerView recyclerView = findViewById(R.id.recyclerViewBanList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new BanListAdapter(bannedEmailsList, (email, position) -> {
            // ПРОВЕРКА ПЕРЕД РАЗБЛОКИРОВКОЙ
            new AlertDialog.Builder(BanListActivity.this)
                    .setTitle("Разблокировка")
                    .setMessage("Вы действительно хотите разблокировать пользователя " + email + "? Он снова сможет писать вам.")
                    .setCancelable(true)
                    .setPositiveButton("Разблокировать", (dialog, which) -> {
                        new Thread(() -> {
                            // Удаляем e-mail из таблицы ban_list
                            boolean isUnbanned = dbService.unbanUser(email);

                            runOnUiThread(() -> {
                                if (isUnbanned) {
                                    bannedEmailsList.remove(position);
                                    adapter.notifyItemRemoved(position);
                                    adapter.notifyItemRangeChanged(position, bannedEmailsList.size());
                                    Toast.makeText(BanListActivity.this, "Пользователь разблокирован", LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(BanListActivity.this, "Ошибка разблокировки", LENGTH_SHORT).show();
                                }
                            });
                        }).start();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });

        recyclerView.setAdapter(adapter);

        // Находим новую кнопку блокировки по Email
        Button buttonAddSpammer = findViewById(R.id.buttonAddStubbornSpammer);

        buttonAddSpammer.setOnClickListener(v -> {
            // 1. Создаем поле ввода текста программно
            final EditText inputEmail = new EditText(BanListActivity.this);
            inputEmail.setHint("example@mail.ru");
            inputEmail.setInputType(TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            inputEmail.setPadding(50, 40, 50, 40); // Добавляем аккуратные отступы для текста

            // 2. Строим всплывающее диалоговое окно
            new AlertDialog.Builder(BanListActivity.this)
                    .setTitle("Заблокировать по Email")
                    .setMessage("Введите Email адрес пользователя, которого хотите внести в чёрный список:")
                    .setView(inputEmail) // Вставляем наше поле ввода внутрь диалога
                    .setCancelable(true)
                    .setPositiveButton("Забанить", (dialog, which) -> {
                        String emailToBan = inputEmail.getText().toString().trim().toLowerCase();

                        // Валидация: проверяем, что поле не пустое и содержит символ @
                        if (emailToBan.contains("@")) {
                            new Thread(() -> {
                                // Записываем нарушителя в таблицу ban_list в SQLite
                                boolean isBanned = dbService.banUser(emailToBan);

                                // Попутно находим чат с ним (если он был) и стираем историю переписки
                                long associatedChatId = dbService.getChatIdByRecipientEmail(emailToBan);
                                if (associatedChatId > 0) {
                                    dbService.deleteChatAndMessages(associatedChatId);
                                }

                                runOnUiThread(() -> {
                                    if (isBanned) {
                                        Toast.makeText(BanListActivity.this, "Пользователь добавлен в чёрный список", LENGTH_SHORT).show();

                                        // Принудительно вызываем обновление списка из базы данных
                                        onResume();
                                    } else {
                                        Toast.makeText(BanListActivity.this, "Пользователь уже заблокирован", LENGTH_SHORT).show();
                                    }
                                });
                            }).start();
                        } else {
                            Toast.makeText(BanListActivity.this, "Некорректный Email адрес!", LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Подгружаем актуальный список из базы данных
        new Thread(() -> {
            List<String> freshBanned = dbService.getBannedUsers();
            runOnUiThread(() -> {
                if (freshBanned != null) {
                    bannedEmailsList.clear();
                    bannedEmailsList.addAll(freshBanned);
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }
}
