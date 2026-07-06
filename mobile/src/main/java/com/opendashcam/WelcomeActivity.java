package com.opendashcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

public class WelcomeActivity extends AppCompatActivity {

    static final int PAGE_FOLDER = 2;
    static final int PAGE_LAST = 3;

    ViewPager viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RecordingOrientationHelper.applyActivityOrientation(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        SectionsPagerAdapter sectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
        viewPager = findViewById(R.id.container);
        viewPager.setAdapter(sectionsPagerAdapter);
    }

    public static class FirstFragment extends Fragment {
        private static final String ARG_LAYOUT_ID = "layout_id";
        private static final String ARG_NEXT_PAGE = "next_page";

        public FirstFragment() {
        }

        public static FirstFragment newInstance(int layoutId) {
            return newInstance(layoutId, -1);
        }

        public static FirstFragment newInstance(int layoutId, int nextPage) {
            FirstFragment fragment = new FirstFragment();
            Bundle args = new Bundle();
            args.putInt(ARG_LAYOUT_ID, layoutId);
            args.putInt(ARG_NEXT_PAGE, nextPage);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View rootView = inflater.inflate(requireArguments().getInt(ARG_LAYOUT_ID), container, false);

            Button endWelcomeButton = rootView.findViewById(R.id.end_welcome);
            if (endWelcomeButton != null) {
                endWelcomeButton.setOnClickListener(view -> finishWelcome());
            }

            ImageView demoRecButton = rootView.findViewById(R.id.demo_rec_widget);
            if (demoRecButton != null) {
                int nextPage = requireArguments().getInt(ARG_NEXT_PAGE, -1);
                if (nextPage >= 0) {
                    demoRecButton.setOnClickListener(view -> {
                        WelcomeActivity activity = (WelcomeActivity) requireActivity();
                        activity.viewPager.setCurrentItem(nextPage, true);
                    });
                }
            }

            return rootView;
        }

        private void finishWelcome() {
            SharedPreferences sharedPref = requireActivity().getSharedPreferences(
                    getString(R.string.db_first_launch_complete_flag),
                    Context.MODE_PRIVATE
            );
            sharedPref.edit()
                    .putString(getString(R.string.db_first_launch_complete_flag), "true")
                    .apply();

            startActivity(new Intent(requireActivity(), MainActivity.class));
            requireActivity().finish();
        }
    }

    public static class FolderFragment extends Fragment {

        private ActivityResultLauncher<Intent> folderPickerLauncher;
        private TextView folderSummaryView;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            folderPickerLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            StorageHelper.setCustomFolderUri(
                                    requireContext(),
                                    result.getData().getData()
                            );
                            updateFolderSummary();
                        }
                    }
            );
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_welcome_folder, container, false);
            folderSummaryView = rootView.findViewById(R.id.welcome_folder_summary);

            Button chooseFolderButton = rootView.findViewById(R.id.welcome_choose_folder);
            chooseFolderButton.setOnClickListener(v ->
                    folderPickerLauncher.launch(StorageHelper.createFolderPickerIntent())
            );

            Button useDefaultButton = rootView.findViewById(R.id.welcome_use_default_folder);
            useDefaultButton.setOnClickListener(v -> {
                StorageHelper.clearCustomFolder(requireContext());
                updateFolderSummary();
            });

            Button nextButton = rootView.findViewById(R.id.welcome_folder_next);
            nextButton.setOnClickListener(v -> {
                WelcomeActivity activity = (WelcomeActivity) requireActivity();
                activity.viewPager.setCurrentItem(WelcomeActivity.PAGE_LAST, true);
            });

            return rootView;
        }

        @Override
        public void onResume() {
            super.onResume();
            updateFolderSummary();
        }

        private void updateFolderSummary() {
            if (folderSummaryView != null) {
                folderSummaryView.setText(StorageHelper.getFolderSummary(requireContext()));
            }
        }
    }

    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        SectionsPagerAdapter(FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return FirstFragment.newInstance(R.layout.fragment_welcome);
                case 1:
                    return FirstFragment.newInstance(R.layout.fragment_welcome_2, PAGE_FOLDER);
                case PAGE_FOLDER:
                    return new FolderFragment();
                case PAGE_LAST:
                    return FirstFragment.newInstance(R.layout.fragment_welcome_3);
                default:
                    return FirstFragment.newInstance(R.layout.fragment_welcome);
            }
        }

        @Override
        public int getCount() {
            return 4;
        }
    }
}
