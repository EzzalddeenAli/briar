package org.briarproject.briar.android.socialbackup.recover;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class OwnerRecoveryModeErrorFragment extends BaseFragment {
	public static final String TAG =
			OwnerRecoveryModeErrorFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private OwnerReturnShardViewModel viewModel;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(OwnerReturnShardViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_recovery_custodian_error_explainer,
				container, false);
		Button button = view.findViewById(R.id.button);
		button.setOnClickListener(e -> viewModel.onErrorTryAgain());
		// TODO cancel button
//		button.setOnClickListener(e -> viewModel.onErrorCancelled());
		return view;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
