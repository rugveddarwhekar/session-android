package org.thoughtcrime.securesms.recipients.ui.sharablegrouplink;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.LiveGroup;
import org.thoughtcrime.securesms.util.BottomSheetUtil;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.Util;

import java.util.Objects;

public final class GroupLinkBottomSheetDialogFragment extends BottomSheetDialogFragment {

  public static final String ARG_GROUP_ID = "group_id";

  public static void show(@NonNull FragmentManager manager, @NonNull GroupId.V2 groupId) {
    GroupLinkBottomSheetDialogFragment fragment = new GroupLinkBottomSheetDialogFragment();
    Bundle                             args     = new Bundle();

    args.putString(ARG_GROUP_ID, groupId.toString());

    fragment.setArguments(args);
    fragment.show(manager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    setStyle(DialogFragment.STYLE_NORMAL,
             ThemeUtil.isDarkTheme(requireContext()) ? R.style.Theme_Signal_RoundedBottomSheet
                                                     : R.style.Theme_Signal_RoundedBottomSheet_Light);

    super.onCreate(savedInstanceState);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.group_link_share_bottom_sheet, container, false);

    View shareViaSignalButton = view.findViewById(R.id.group_link_bottom_sheet_share_via_signal_button);
    View copyButton           = view.findViewById(R.id.group_link_bottom_sheet_copy_button);
    View viewQrButton         = view.findViewById(R.id.group_link_bottom_sheet_qr_code_button);
    View shareBySystemButton  = view.findViewById(R.id.group_link_bottom_sheet_share_via_system_button);

    GroupId.V2 groupId = GroupId.parseOrThrow(Objects.requireNonNull(requireArguments().getString(ARG_GROUP_ID))).requireV2();

    LiveGroup liveGroup = new LiveGroup(groupId);

    liveGroup.getGroupLink().observe(getViewLifecycleOwner(), groupLink -> {
      if (!groupLink.isEnabled()) {
        Toast.makeText(requireContext(), R.string.GroupLinkBottomSheet_the_link_is_not_currently_active, Toast.LENGTH_SHORT).show();
        dismiss();
        return;
      }

      shareViaSignalButton.setOnClickListener(v -> dismiss()); // Todo [Alan] GV2 Add share within signal
      shareViaSignalButton.setVisibility(View.GONE);

      copyButton.setOnClickListener(v -> {
        Context context = requireContext();
        Util.copyToClipboard(context, groupLink.getUrl());
        Toast.makeText(context, R.string.GroupLinkBottomSheet_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        dismiss();
      });

      viewQrButton.setOnClickListener(v -> dismiss()); // Todo [Alan] GV2 Add share QR within signal
      viewQrButton.setVisibility(View.GONE);

      shareBySystemButton.setOnClickListener(v -> {
          ShareCompat.IntentBuilder.from(requireActivity())
                                   .setType("text/plain")
                                   .setText(groupLink.getUrl())
                                   .startChooser();

          dismiss();
        });
    });

    return view;
  }

  @Override
  public void show(@NonNull FragmentManager manager, @Nullable String tag) {
    BottomSheetUtil.show(manager, tag, this);
  }
}
