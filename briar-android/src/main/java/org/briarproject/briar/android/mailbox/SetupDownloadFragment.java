package org.briarproject.briar.android.mailbox;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.R;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.EXTRA_TEXT;
import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SetupDownloadFragment extends Fragment {

	static final String TAG = SetupDownloadFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private MailboxViewModel viewModel;

	private CameraPermissionManager permissionManager;

	private final ActivityResultLauncher<String[]> permissionLauncher =
			registerForActivityResult(new RequestMultiplePermissions(), r -> {
				permissionManager.onRequestPermissionResult(r);
				if (permissionManager.checkPermissions()) {
					scanCode();
				}
			});

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		FragmentActivity activity = requireActivity();
		getAndroidComponent(activity).inject(this);
		viewModel = new ViewModelProvider(activity, viewModelFactory)
				.get(MailboxViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_mailbox_setup_download,
				container, false);

		permissionManager = new CameraPermissionManager(requireActivity(),
				permissionLauncher::launch);

		Button shareLinkButton = v.findViewById(R.id.shareLinkButton);
		shareLinkButton.setOnClickListener(this::shareLink);

		Button scanButton = v.findViewById(R.id.scanButton);
		scanButton.setOnClickListener(view -> {
			if (permissionManager.checkPermissions()) {
				scanCode();
			}
		});
		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.mailbox_setup_title);
		// Permissions may have been granted manually while we were stopped
		permissionManager.resetPermissions();
	}

	private void shareLink(View v) {
		Context ctx = requireContext();
		String download = ctx.getString(R.string.mailbox_share_download);
		String text = ctx.getString(R.string.mailbox_share_text, download);

		Intent sendIntent = new Intent();
		sendIntent.setAction(ACTION_SEND);
		sendIntent.putExtra(EXTRA_TEXT, text);
		sendIntent.setType("text/plain");

		Intent shareIntent = Intent.createChooser(sendIntent, null);
		try {
			startActivity(shareIntent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(ctx, R.string.error_start_activity, LENGTH_LONG)
					.show();
		}
	}

	private void scanCode() {
		viewModel.onScanButtonClicked();
	}

}
