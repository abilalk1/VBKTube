package com.vidownphone.app.ui.browser;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.vidownphone.app.R;
import com.vidownphone.app.databinding.BottomSheetDetectedMediaBinding;
import com.vidownphone.app.model.DetectedStream;

import java.util.ArrayList;
import java.util.List;

public class DetectedMediaBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_PAGE_URL = "arg_page_url";
    private static final String ARG_STREAMS = "arg_streams";

    /**
     * Implemented by the host (BrowserFragment). The sheet resolves it through getParentFragment()
     * / host activity, so it keeps working after rotation or process recreation.
     */
    public interface Callbacks {
        void onAnalyzeFullPageWithYtDlp(String pageUrl);
        void onDownloadDetectedStream(DetectedStream stream);
        default void onDownloadAllDetectedStreams(List<DetectedStream> streams) {}
        void onClearStreams();
    }

    private BottomSheetDetectedMediaBinding binding;

    public static DetectedMediaBottomSheet newInstance(String pageUrl, ArrayList<DetectedStream> streams) {
        DetectedMediaBottomSheet sheet = new DetectedMediaBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PAGE_URL, pageUrl);
        args.putSerializable(ARG_STREAMS, streams);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    private Callbacks resolveCallbacks() {
        androidx.fragment.app.Fragment parent = getParentFragment();
        if (parent instanceof Callbacks) {
            return (Callbacks) parent;
        }
        Context host = getActivity();
        if (host instanceof Callbacks) {
            return (Callbacks) host;
        }
        return null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = BottomSheetDetectedMediaBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String pageUrl = getArguments() != null ? getArguments().getString(ARG_PAGE_URL, "") : "";
        List<DetectedStream> streams = getArguments() != null
                ? (List<DetectedStream>) getArguments().getSerializable(ARG_STREAMS)
                : new ArrayList<>();

        binding.tvPageUrl.setText(pageUrl);

        binding.btnYtdlpAnalyzePage.setOnClickListener(v -> {
            Callbacks callbacks = resolveCallbacks();
            if (callbacks != null && !pageUrl.isEmpty()) {
                callbacks.onAnalyzeFullPageWithYtDlp(pageUrl);
                dismissAllowingStateLoss();
            } else {
                // Never silently do nothing
                Toast.makeText(requireContext(), R.string.error_empty_url, Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnClearDetected.setOnClickListener(v -> {
            Callbacks callbacks = resolveCallbacks();
            if (callbacks != null) {
                callbacks.onClearStreams();
            }
            binding.rvDetectedMedia.setVisibility(View.GONE);
            binding.tvNoStreams.setVisibility(View.VISIBLE);
            binding.btnClearDetected.setEnabled(false);
        });

        if (streams == null || streams.isEmpty()) {
            binding.tvNoStreams.setVisibility(View.VISIBLE);
            binding.rvDetectedMedia.setVisibility(View.GONE);
            binding.btnClearDetected.setEnabled(false);
        } else {
            binding.tvNoStreams.setVisibility(View.GONE);
            binding.rvDetectedMedia.setVisibility(View.VISIBLE);
            binding.btnClearDetected.setEnabled(true);
            DetectedMediaAdapter adapter = new DetectedMediaAdapter(streams, stream -> {
                Callbacks callbacks = resolveCallbacks();
                if (callbacks == null) {
                    // Nothing was enqueued: do not mark the row as downloaded
                    return false;
                }
                callbacks.onDownloadDetectedStream(stream);
                // Keep sheet open so user can download carousel slides / story items one by one!
                return true;
            });
            binding.rvDetectedMedia.setLayoutManager(new LinearLayoutManager(requireContext()));
            binding.rvDetectedMedia.setAdapter(adapter);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
