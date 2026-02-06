package uz.kosmostar.vokall;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.util.ArrayList;
import java.util.List;
import uz.kosmostar.vokall.ConnectionsActivity.Endpoint;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    private final List<Endpoint> devices = new ArrayList<>();
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(Endpoint endpoint);
    }

    public DeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void addDevice(Endpoint endpoint) {
        if (!devices.contains(endpoint)) {
            devices.add(endpoint);
            notifyItemInserted(devices.size() - 1);
        }
    }

    public void removeDevice(String endpointId) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getId().equals(endpointId)) {
                devices.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void clear() {
        devices.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Endpoint device = devices.get(position);
        holder.name.setText(device.getName());
        holder.card.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final MaterialCardView card;

        ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.device_name);
            card = (MaterialCardView) view;
        }
    }
}