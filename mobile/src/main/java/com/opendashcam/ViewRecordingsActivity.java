package com.opendashcam;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.opendashcam.models.Recording;
import com.opendashcam.presenters.IViewRecordings;
import com.opendashcam.presenters.ViewRecordingsPresenter;

import java.util.ArrayList;

public class ViewRecordingsActivity extends AppCompatActivity implements IViewRecordings.View {

    private final OverlayLifecycle overlayLifecycle = new OverlayLifecycle();
    private RecyclerView recyclerView;
    private ViewRecordingsRecyclerViewAdapter adapter;
    private View layoutListEmpty;
    private IViewRecordings.Presenter presenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RecordingOrientationHelper.applyActivityOrientation(this);
        super.onCreate(savedInstanceState);
        overlayLifecycle.onCreate(this);
        setContentView(R.layout.activity_view_recordings);
        presenter = new ViewRecordingsPresenter(this);
        initRecyclerView();
    }

    @Override
    protected void onStart() {
        super.onStart();
        presenter.onStartView();
    }

    @Override
    protected void onStop() {
        presenter.onStopView();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        overlayLifecycle.onDestroy(this);
        super.onDestroy();
    }

    @Override
    public void updateRecordingsList(ArrayList<Recording> recordingsList) {
        if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }

        if (adapter != null) {
            adapter.populateList(recordingsList);
        }

        if (recordingsList == null || recordingsList.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            layoutListEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            layoutListEmpty.setVisibility(View.GONE);
        }
    }

    @Override
    public Activity getActivity() {
        return this;
    }

    private void initRecyclerView() {
        recyclerView = findViewById(R.id.recycler_view);
        layoutListEmpty = findViewById(R.id.layout_list_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ViewRecordingsRecyclerViewAdapter(
                this,
                recording -> presenter.onRecordingsItemPressed(recording)
        );
        recyclerView.setAdapter(adapter);
    }
}
