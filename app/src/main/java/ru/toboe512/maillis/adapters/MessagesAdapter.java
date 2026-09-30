package ru.toboe512.maillis.adapters;


import static android.content.Context.MODE_PRIVATE;

import static ru.toboe512.maillis.consts.Consts.PREFS_NAME;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import ru.toboe512.maillis.dto.MessageDto;
import ru.toboe512.maillis.utils.MessageDiffCallback;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<MessageDto> messageDtoList;
    private final Context context;

    private final DBService dbService;
    private EmailClient emailClient;

    public MessagesAdapter(Context context, List<MessageDto> messageDtoList, EmailClient emailClient, DBService dbService) {
        this.context = context;
        this.messageDtoList = messageDtoList;
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
        MessageDto messageDto = messageDtoList.get(position);

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.cardMessage.getLayoutParams();

        // 1. НАСТРОЙКА ОТОБРАЖЕНИЯ КОНТЕНТА (ТЕКСТ ИЛИ ФОТО)
        // Проверяем тип сообщения, сохраненный в SQLite
        if ("IMAGE".equals(messageDto.getMessageType()) && messageDto.getFilePath() != null) {

            // Скрываем текстовое поле, чтобы оно не занимало место
            holder.textViewText.setVisibility(View.GONE);

            // Показываем область изображения
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(View.VISIBLE);

                // Безопасно декодируем и загружаем файл .jpg из изолированного кэша телефона
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(messageDto.getFilePath());
                if (bitmap != null) {
                    holder.imageViewPhoto.setImageBitmap(bitmap);
                } else {
                    // Если файл поврежден или удален, показываем системную заглушку ошибки
                    holder.imageViewPhoto.setImageResource(android.R.drawable.ic_menu_report_image);
                }
            }
        } else {
            // Если это обычное текстовое сообщение
            if (holder.imageViewPhoto != null) {
                holder.imageViewPhoto.setVisibility(View.GONE); // Прячем ImageView
            }
            holder.textViewText.setVisibility(View.VISIBLE);
            holder.textViewText.setText(messageDto.getText());
        }

        // 2. НАСТРОЙКА СТАТУСА И ГАЛОЧЕК (Наш старый отлаженный код)
        if (messageDto.isMine()) {
            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(View.VISIBLE);
                int status = messageDto.getStatus();
                switch (status) {
                    case 0:
                        holder.textViewStatus.setText("🕒");// Ожидание сети
                        break;
                    case 3:
                        holder.textViewStatus.setText("⏳");// В процессе отправки SMTP
                        break;
                    case 1:
                        holder.textViewStatus.setText("✓");// На сервере Mail.ru
                        break;
                    case 2:
                        holder.textViewStatus.setText("✓✓");// Прочитано другом
                        break;
                    default:
                        holder.textViewStatus.setText("✓");
                        break;
                }
            }
        } else {
            if (holder.textViewStatus != null) {
                holder.textViewStatus.setVisibility(View.GONE);
            }
        }
        holder.cardMessage.setLayoutParams(params);

        // ОБРАБОТКА ДОЛГОГО КЛИКА НА СООБЩЕНИЕ
        holder.itemView.setOnLongClickListener(v -> {
            showDeleteMessageDialog(messageDto, position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return messageDtoList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        CardView cardMessage;
        LinearLayout layoutBackground;
        TextView textViewSender;
        TextView textViewText;
        TextView textViewStatus; // НОВОЕ ПОЛЕ
        ImageView imageViewPhoto;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            cardMessage = itemView.findViewById(R.id.cardMessage);
            layoutBackground = itemView.findViewById(R.id.layoutMessageBackground);
            textViewSender = itemView.findViewById(R.id.textViewSender);
            textViewText = itemView.findViewById(R.id.textViewMessageText);
            textViewStatus = itemView.findViewById(R.id.textViewMessageStatus);
            imageViewPhoto = itemView.findViewById(R.id.imageViewMessagePhoto);
        }
    }

    // Всплывающее окно подтверждения удаления
    private void showDeleteMessageDialog(MessageDto messageDto, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Удалить сообщение");
        builder.setMessage("Вы уверены, что хотите удалить это сообщение?");

        builder.setPositiveButton("Удалить", (dialog, which) -> {
            deleteMessageFromDb(messageDto.getId(), position);
            // ЗАПУСКАЕМ СЕТЕВОЕ УДАЛЕНИЕ С СЕРВЕРА MAIL.RU
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            emailClient.deleteMessageFromMailRuServer(messageDto.getServerUid(), prefs);
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
            messageDtoList.remove(position);

            // Красивая плавная анимация исчезновения сообщения из списка
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, messageDtoList.size());

            Toast.makeText(context, "Сообщение удалено", Toast.LENGTH_SHORT).show();
        } else {
            // Если сообщение было временным (не успело записаться в БД)
            messageDtoList.remove(position);
            notifyItemRemoved(position);
        }
    }

    // Внутри класса MessagesAdapter:
    public void setMessages(List<MessageDto> newMessageDtos) {
        // Вычисляем разницу между старым списком (this.messageList) и новым
        MessageDiffCallback diffCallback = new MessageDiffCallback(this.messageDtoList, newMessageDtos);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        // Обновляем данные в памяти адаптера
        this.messageDtoList.clear();
        this.messageDtoList.addAll(newMessageDtos);

        // Применяем точечные анимации (notifyItemInserted, notifyItemChanged автоматически!)
        diffResult.dispatchUpdatesTo(this);
    }
}
