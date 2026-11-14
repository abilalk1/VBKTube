package com.vidownphone.app.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vidownphone.app.R;
import com.vidownphone.app.databinding.ItemBrowserShortcutBinding;
import com.vidownphone.app.model.BrowserShortcut;

import java.util.ArrayList;
import java.util.List;

public class ShortcutAdapter extends RecyclerView.Adapter<ShortcutAdapter.ShortcutViewHolder> {

    public interface ShortcutClickListener {
        void onShortcutClicked(BrowserShortcut shortcut);
        void onShortcutDeleteClicked(BrowserShortcut shortcut);
    }

    private final List<BrowserShortcut> shortcuts = new ArrayList<>();
    private final ShortcutClickListener listener;

    public ShortcutAdapter(ShortcutClickListener listener) {
        this.listener = listener;
    }

    public void setShortcuts(List<BrowserShortcut> list) {
        shortcuts.clear();
        if (list != null) {
            shortcuts.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ShortcutViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBrowserShortcutBinding binding = ItemBrowserShortcutBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ShortcutViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ShortcutViewHolder holder, int position) {
        holder.bind(shortcuts.get(position));
    }

    @Override
    public int getItemCount() {
        return shortcuts.size();
    }

    /** Built-in shortcuts store a frozen-language subtitle in prefs; resolve it from resources instead. */
    private static String resolveSubtitle(Context ctx, BrowserShortcut item) {
        if (!item.isRemovable() && item.getId() != null) {
            switch (item.getId()) {
                case "yt": return ctx.getString(R.string.plat_youtube_sub);
                case "ig": return ctx.getString(R.string.plat_instagram_sub);
                case "tw": return ctx.getString(R.string.plat_twitter_sub);
                case "tt": return ctx.getString(R.string.plat_tiktok_sub);
                case "rd": return ctx.getString(R.string.shortcut_sub_reddit);
                case "fb": return ctx.getString(R.string.shortcut_sub_facebook);
                default: break;
            }
        }
        return item.getSubtitle();
    }

    class ShortcutViewHolder extends RecyclerView.ViewHolder {
        private final ItemBrowserShortcutBinding binding;

        public ShortcutViewHolder(ItemBrowserShortcutBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(BrowserShortcut item) {
            Context ctx = binding.getRoot().getContext();
            binding.tvShortcutTitle.setText(item.getTitle());
            binding.tvShortcutSubtitle.setText(resolveSubtitle(ctx, item));

            String type = item.getIconType() != null ? item.getIconType() : "custom";
            int icon;
            switch (type) {
                case "youtube": icon = R.drawable.ic_platform_play; break;
                case "instagram": icon = R.drawable.ic_platform_camera; break;
                case "twitter": icon = R.drawable.ic_platform_chat; break;
                case "tiktok": icon = R.drawable.ic_platform_music; break;
                case "facebook": icon = R.drawable.ic_platform_group; break;
                case "reddit": icon = R.drawable.ic_platform_forum; break;
                case "sports": icon = R.drawable.ic_video; break;
                default: icon = R.drawable.ic_browser; break;
            }
            binding.cardIconContainer.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.brand_social_bg));
            binding.ivShortcutIcon.setImageResource(icon);
            binding.ivShortcutIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary)));

            if (item.isRemovable()) {
                binding.btnDeleteShortcut.setVisibility(View.VISIBLE);
                binding.btnDeleteShortcut.setOnClickListener(v -> {
                    if (listener != null) listener.onShortcutDeleteClicked(item);
                });
                binding.cardShortcut.setOnLongClickListener(v -> {
                    if (listener != null) listener.onShortcutDeleteClicked(item);
                    return true;
                });
            } else {
                binding.btnDeleteShortcut.setVisibility(View.GONE);
                binding.cardShortcut.setOnLongClickListener(null);
            }

            binding.cardShortcut.setOnClickListener(v -> {
                if (listener != null) listener.onShortcutClicked(item);
            });
        }
    }
}
