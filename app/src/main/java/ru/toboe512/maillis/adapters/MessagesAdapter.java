package ru.toboe512.maillis.adapters;


import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;
import ru.toboe512.maillis.client.EmailClient;
import ru.toboe512.maillis.db.DBService;
import ru.toboe512.maillis.dto.Message;
import ru.toboe512.maillis.utils.MessageDiffCallback;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<Message> messageList;
    private final Context context;

    private final DBService dbService;
    private EmailClient emailClient;

    public MessagesAdapter(Context context, List<Message> messageList, EmailClient emailClient, DBService dbService) {
        this.context = context;
        this.messageList = messageList;
        this.emailClient = emailClient;
        this.dbService = dbService;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messageList.get(position);
        holder.textViewText.setText(message.getText());

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.cardMessage.getLayoutParams();

        if (message.isMine()) {
            // Наше сообщение: прижимаем вправо, красим в приятный голубой, скрываем email
            params.removeRule(RelativeLayout.ALIGN_PARENT_START);
            params.addRule(RelativeLayout.ALIGN_PARENT_END);
            holder.layoutBackground.setBackgroundColor(Color.parseColor("#E3F2FD"));
            holder.textViewSender.setVisibility(View.GONE);
        } else {
            // Чужое сообщение: прижимаем влево, красим в серый, показываем автора
            params.removeRule(RelativeLayout.ALIGN_PARENT_END);
            params.addRule(RelativeLayout.ALIGN_PARENT_START);
            holder.layoutBackground.setBackgroundColor(Color.parseColor("#F5F5F5"));
            holder.textViewSender.setVisibility(View.VISIBLE);
            holder.textViewSender.setText(message.getSenderEmail());
        }
        holder.cardMessage.setLayoutParams(params);

        // ОБРАБОТКА ДОЛГОГО КЛИКА НА СООБЩЕНИЕ
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteMessageDialog(message, position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        CardView cardMessage;
        LinearLayout layoutBackground;
        TextView textViewSender, textViewText;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            cardMessage = itemView.findViewById(R.id.cardMessage);
            layoutBackground = itemView.findViewById(R.id.layoutMessageBackground);
            textViewSender = itemView.findViewById(R.id.textViewSender);
            textViewText = itemView.findViewById(R.id.textViewMessageText);
        }
    }

    // Всплывающее окно подтверждения удаления
    private void showDeleteMessageDialog(Message message, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить сообщение");
        builder.setMessage("Вы уверены, что хотите удалить это сообщение?");

        builder.setPositiveButton("Удалить", (dialog, which) -> {
            deleteMessageFromDb(message.getId(), position);
            // ЗАПУСКАЕМ СЕТЕВОЕ УДАЛЕНИЕ С СЕРВЕРА MAIL.RU
            emailClient.deleteMessageFromMailRuServer(message.getServerUid(), context);
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Удаление строки из SQLite
    private void deleteMessageFromDb(int messageId, int position) {

        // Удаляем строго по первичному ключу ID сообщения
        int rowsDeleted = dbService.deleteMessage(messageId);

        if (rowsDeleted > 0) {
            messageList.remove(position);

            // Красивая плавная анимация исчезновения сообщения из списка
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, messageList.size());

            Toast.makeText(context, "Сообщение удалено", Toast.LENGTH_SHORT).show();
        } else {
            // Если сообщение было временным (не успело записаться в БД)
            messageList.remove(position);
            notifyItemRemoved(position);
        }
    }

    // Внутри класса MessagesAdapter:
    public void setMessages(List<Message> newMessages) {
        // Вычисляем разницу между старым списком (this.messageList) и новым
        MessageDiffCallback diffCallback = new MessageDiffCallback(this.messageList, newMessages);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        // Обновляем данные в памяти адаптера
        this.messageList.clear();
        this.messageList.addAll(newMessages);

        // Применяем точечные анимации (notifyItemInserted, notifyItemChanged автоматически!)
        diffResult.dispatchUpdatesTo(this);
    }
}
