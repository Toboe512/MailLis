package ru.toboe512.maillis.adapters;


import android.app.AlertDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.db.ChatDbHelper;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.Contact;

public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ContactViewHolder> {

    private final List<Contact> contactList;
    private final Context context;
    private final DBService dbService;

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        TextView textViewName, textViewEmail;
        CheckBox checkBox;

        public ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewName = itemView.findViewById(R.id.textViewContactName);
            textViewEmail = itemView.findViewById(R.id.textViewContactEmail);
            checkBox = itemView.findViewById(R.id.checkBoxSelect);
        }
    }

    // ОБНОВЛЕННЫЙ КОНСТРУКТОР: теперь принимает контекст для работы с БД и диалогами
    public ContactsAdapter(Context context, DBService dbService, List<Contact> contactList) {
        this.context = context;
        this.contactList = contactList;
        this.dbService = dbService;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact_select, parent, false);
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        Contact contact = contactList.get(position);
        holder.textViewName.setText(contact.getName());
        holder.textViewEmail.setText(contact.getEmail());
        holder.checkBox.setChecked(contact.isSelected());

        // Обычный клик — ставит/убирает галочку выбора в группу
        holder.itemView.setOnClickListener(v -> {
            contact.setSelected(!contact.isSelected());
            holder.checkBox.setChecked(contact.isSelected());
        });

        // ДОЛГИЙ КЛИК — вызывает удаление контакта
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteDialog(contact, position);
            return true; // Возвращаем true, чтобы обычный клик не срабатывал одновременно
        });
    }

    @Override
    public int getItemCount() {
        return contactList.size();
    }

    // Метод показа всплывающего окна подтверждения удаления
    private void showDeleteDialog(Contact contact, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить контакт");
        builder.setMessage("Вы уверены, что хотите удалить контакт " + contact.getName() + "?");

        // Кнопка "Да"
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            deleteContactFromDb(contact.getId(), position);
        });

        // Кнопка "Отмена"
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Нативное удаление строки из SQLite по ее ID
    private void deleteContactFromDb(int contactId, int position) {

        Contact contact = dbService.getContactById(contactId);
        boolean isDeleted = dbService.deleteContact(contactId);

        if (isDeleted) {
            dbService.cleanUpPublicKeyIfNoContexts(contact.getEmail());
            // Удаляем элемент из текущего массива памяти
            contactList.remove(position);

            // Уведомляем список, что элемент удален (для красивой плавной анимации)
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, contactList.size());

            Toast.makeText(context, "Контакт успешно удален", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "Ошибка при удалении контакта", Toast.LENGTH_SHORT).show();
        }
    }
}