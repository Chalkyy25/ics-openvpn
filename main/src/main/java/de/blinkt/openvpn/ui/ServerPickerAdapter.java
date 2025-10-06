package de.blinkt.openvpn.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.api.dto.ServerItem;

public class ServerPickerAdapter extends RecyclerView.Adapter<ServerPickerAdapter.VH> {

    public interface OnServerClick { void onServerSelected(ServerItem s); }

    private final List<ServerItem> data;
    private final OnServerClick cb;

    private int selectedId = -1; // persist selection across binds

    public ServerPickerAdapter(List<ServerItem> data, OnServerClick cb) {
        this.data = data;
        this.cb = cb;
        setHasStableIds(true);
    }

    public void setSelectedId(int id) {
        if (selectedId == id) return;
        int old = selectedId;
        selectedId = id;
        if (old != -1) {
            int oi = indexOf(old);
            if (oi >= 0) notifyItemChanged(oi);
        }
        int ni = indexOf(id);
        if (ni >= 0) notifyItemChanged(ni);
    }

    public int getSelectedId() { return selectedId; }

    @Override public long getItemId(int position) {
        // Improves focus/animation stability
        return data.get(position).id;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ServerItem s = data.get(pos);

        h.name.setText(s.name == null ? "Unnamed" : s.name);
        h.sub.setText(s.ip == null ? "" : s.ip);

        boolean isSelected = (s.id == selectedId);

        // Background: solid glow if selected, focus selector otherwise
        h.root.setBackgroundResource(isSelected
                ? R.drawable.aio_glow_purple
                : R.drawable.aio_focus_glow_selector);

        // Elevation animator from XML (optional)
        h.root.setStateListAnimator(
                h.root.getResources().getDrawable(
                        R.drawable.aio_focus_glow_selector) != null
                        ? h.root.getStateListAnimator()
                        : null);

        // Scale to highlight
        applyScale(h.root, isSelected, false);

        // Click → select + callback
        h.itemView.setOnClickListener(v -> {
            if (selectedId != s.id) {
                int old = selectedId;
                selectedId = s.id;
                notifyItemChanged(h.getBindingAdapterPosition());
                if (old != -1) {
                    int oi = indexOf(old);
                    if (oi >= 0) notifyItemChanged(oi);
                }
            }
            if (cb != null) cb.onServerSelected(s);
        });

        // DPAD/TV focus hint (keeps selection glow even when not focused)
        h.itemView.setOnFocusChangeListener((v, hasFocus) ->
                applyScale(h.root, isSelected, hasFocus));
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private int indexOf(int id) {
        if (data == null) return -1;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).id == id) return i;
        }
        return -1;
    }

    private void applyScale(View v, boolean isSelected, boolean hasFocus) {
        float target = isSelected ? 1.06f : (hasFocus ? 1.03f : 1.0f);
        v.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(140)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView name, sub;
        VH(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.cardRoot);   // <- wrapper in item_server.xml
            name = itemView.findViewById(R.id.tvName);
            sub  = itemView.findViewById(R.id.tvSubtitle);
        }
    }
}