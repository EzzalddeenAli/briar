package org.briarproject.briar.android.socialbackup.recover;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.R;
import org.briarproject.briar.android.fragment.BaseFragment;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import static android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
import static android.view.inputmethod.EditorInfo.IME_ACTION_NEXT;
import static org.briarproject.briar.android.util.UiUtils.enterPressed;
import static org.briarproject.briar.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
abstract class RestoreAccountFragment extends BaseFragment implements TextWatcher,
		TextView.OnEditorActionListener, View.OnClickListener {

	private final static String STATE_KEY_CLICKED = "setupFragmentClicked";

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	RestoreAccountViewModel viewModel;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		viewModel = new ViewModelProvider(requireActivity())
				.get(RestoreAccountViewModel.class);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.help_action, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_help) {
			showOnboardingDialog(getContext(), getHelpText());
			return true;
		} else {
			return super.onOptionsItemSelected(item);
		}
	}

	protected abstract String getHelpText();

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count,
			int after) {
		// noop
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before,
			int count) {
		// noop
	}

	@Override
	public boolean onEditorAction(TextView textView, int actionId,
			@Nullable KeyEvent keyEvent) {
		if (actionId == IME_ACTION_NEXT || actionId == IME_ACTION_DONE ||
				enterPressed(actionId, keyEvent)) {
			onClick(textView);
			return true;
		}
		return false;
	}

	@Override
	public void afterTextChanged(Editable editable) {
		// noop
	}
}