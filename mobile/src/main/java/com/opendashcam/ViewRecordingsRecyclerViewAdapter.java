package com.opendashcam;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.opendashcam.models.Recording;

import java.util.ArrayList;

public class ViewRecordingsRecyclerViewAdapter extends RecyclerView.Adapter<ViewRecordingsRecyclerViewAdapter.RecordingHolder> {

    public interface RecordingListener {
        void onItemClick(Recording recording);
    }

    private final RecordingListener recordingsListener;
    private final ArrayList<Recording> recordingsList = new ArrayList<>();
    private final int width;
    private final int height;
    private final Context context;

    ViewRecordingsRecyclerViewAdapter(Context context, RecordingListener clickListener) {
        this.context = context;
        this.recordingsListener = clickListener;
        width = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 150, context.getResources().getDisplayMetrics());
        height = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 100, context.getResources().getDisplayMetrics());
    }

    @NonNull
    @Override
    public RecordingHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_recordings_row, parent, false);
        return new RecordingHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingHolder holder, int position) {
        final Recording recItem = recordingsList.get(holder.getAdapterPosition());
        if (recItem == null) {
            return;
        }

        holder.label.setText(recItem.getDateSaved());
        holder.dateTime.setText(recItem.getTimeSaved());
        holder.starred.setOnCheckedChangeListener(null);
        holder.starred.setChecked(recItem.isStarred());

        holder.itemView.setOnClickListener(v -> {
            if (recordingsListener != null) {
                recordingsListener.onItemClick(recItem);
            }
        });

        holder.starred.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                recItem.toggleStar(isChecked);
            }
        });

        showMediaThumbnail(holder, recItem.getFilePath());
    }

    void populateList(ArrayList<Recording> dataset) {
        recordingsList.clear();
        recordingsList.addAll(dataset);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return recordingsList.size();
    }

    private void showMediaThumbnail(@NonNull RecordingHolder holder, String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }

        if (RecordingMediaType.fromPath(filePath) == RecordingMediaType.AUDIO) {
            holder.thumbnail.setImageResource(R.drawable.ic_videocam_red_128dp);
            return;
        }

        Glide.with(context)
                .load(filePath)
                .dontAnimate()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_videocam_red_128dp)
                .override(width, height)
                .into(holder.thumbnail);
    }

    static class RecordingHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView label;
        TextView dateTime;
        CheckBox starred;

        RecordingHolder(View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            label = itemView.findViewById(R.id.recordingDate);
            dateTime = itemView.findViewById(R.id.recordingTime);
            starred = itemView.findViewById(R.id.starred);
        }
    }
}
