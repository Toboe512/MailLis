package ru.toboe512.maillis.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.toboe512.maillis.R;

public class BanListAdapter extends RecyclerView.Adapter<BanListAdapter.BanViewHolder> {

    private final List<String> bannedEmails;
    private final OnUnbanClickListener unbanClickListener;

    public interface OnUnbanClickListener {
        void onUnbanClick(String email, int position);
    }

    public BanListAdapter(List<String> bannedEmails, OnUnbanClickListener unbanClickListener) {
        this.bannedEmails = bannedEmails;
        this.unbanClickListener = unbanClickListener;
    }

    @NonNull
    @Override
    public BanViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater
                .from(parent.getContext())
                .inflate(R.layout.item_banned_user, parent, false);
        return new BanViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BanViewHolder holder, int position) {
        String email = bannedEmails.get(position);
        holder.textViewEmail.setText(email);

        holder.buttonUnban.setOnClickListener(v -> {
            if (unbanClickListener != null) {
                unbanClickListener.onUnbanClick(email, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return bannedEmails.size();
    }

    static class BanViewHolder extends RecyclerView.ViewHolder {
        TextView textViewEmail;
        ImageButton buttonUnban;

        public BanViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewEmail = itemView.findViewById(R.id.textViewBannedEmail);
            buttonUnban = itemView.findViewById(R.id.imageButtonUnban);
        }
    }
}
