package org.briarproject.briar.android.threaded;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.support.annotation.CallSuper;
import android.support.annotation.UiThread;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.R;
import org.briarproject.briar.android.threaded.ThreadItemAdapter.ThreadItemListener;
import org.briarproject.briar.android.view.AuthorView;

@UiThread
@NotNullByDefault
public abstract class BaseThreadItemViewHolder<I extends ThreadItem>
		extends RecyclerView.ViewHolder {

	private final static int ANIMATION_DURATION = 5000;

	protected final TextView textView;
	private final ViewGroup layout;
	private final AuthorView author;
	private final View topDivider;

	public BaseThreadItemViewHolder(View v) {
		super(v);

		layout = (ViewGroup) v.findViewById(R.id.layout);
		textView = (TextView) v.findViewById(R.id.text);
		author = (AuthorView) v.findViewById(R.id.author);
		topDivider = v.findViewById(R.id.top_divider);
	}

	// TODO improve encapsulation, so we don't need to pass the adapter here
	@CallSuper
	public void bind(final ThreadItemAdapter<I> adapter,
			final ThreadItemListener<I> listener, final I item, int pos) {

		textView.setText(StringUtils.trim(item.getText()));

		if (pos == 0) {
			topDivider.setVisibility(View.INVISIBLE);
		} else {
			topDivider.setVisibility(View.VISIBLE);
		}

		author.setAuthor(item.getAuthor());
		author.setDate(item.getTimestamp());
		author.setAuthorStatus(item.getStatus());

		if (item.equals(adapter.getReplyItem())) {
			layout.setBackgroundColor(ContextCompat
					.getColor(getContext(), R.color.forum_cell_highlight));
		} else if (item.equals(adapter.getAddedItem())) {
			layout.setBackgroundColor(ContextCompat
					.getColor(getContext(), R.color.forum_cell_highlight));
			animateFadeOut(adapter, adapter.getAddedItem());
			adapter.clearAddedItem();
		} else {
			layout.setBackgroundColor(ContextCompat
					.getColor(getContext(), R.color.window_background));
		}
	}

	private void animateFadeOut(final ThreadItemAdapter<I> adapter,
			final I addedItem) {

		setIsRecyclable(false);
		ValueAnimator anim = new ValueAnimator();
		adapter.addAnimatingItem(addedItem, anim);
		ColorDrawable viewColor = (ColorDrawable) layout.getBackground();
		anim.setIntValues(viewColor.getColor(), ContextCompat
				.getColor(getContext(), R.color.window_background));
		anim.setEvaluator(new ArgbEvaluator());
		anim.addListener(new Animator.AnimatorListener() {
			@Override
			public void onAnimationStart(Animator animation) {

			}

			@Override
			public void onAnimationEnd(Animator animation) {
				setIsRecyclable(true);
				adapter.removeAnimatingItem(addedItem);
			}

			@Override
			public void onAnimationCancel(Animator animation) {
				setIsRecyclable(true);
				adapter.removeAnimatingItem(addedItem);
			}

			@Override
			public void onAnimationRepeat(Animator animation) {

			}
		});
		anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator valueAnimator) {
				layout.setBackgroundColor(
						(Integer) valueAnimator.getAnimatedValue());
			}
		});
		anim.setDuration(ANIMATION_DURATION);
		anim.start();
	}

	protected Context getContext() {
		return textView.getContext();
	}

}