package org.briarproject.android;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.view.Gravity.CENTER;
import static java.util.logging.Level.INFO;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_MATCH;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.briarproject.R;
import org.briarproject.android.util.LayoutUtils;
import org.briarproject.api.db.DatabaseConfig;

import roboguice.RoboGuice;
import roboguice.activity.RoboSplashActivity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.inject.Injector;

public class SplashScreenActivity extends RoboSplashActivity {

	private static final Logger LOG =
			Logger.getLogger(SplashScreenActivity.class.getName());

	// This build expires on 7 February 2014
	private static final long EXPIRY_DATE = 1391731200 * 1000L;
	// Default log level - change this to OFF for release builds
	private static final Level DEFAULT_LOG_LEVEL = INFO;

	private long start = System.currentTimeMillis();

	public SplashScreenActivity() {
		Logger.getLogger("").setLevel(DEFAULT_LOG_LEVEL);
		minDisplayMs = 500;
	}

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_MATCH);
		layout.setGravity(CENTER);
		layout.setBackgroundColor(Color.WHITE);

		int pad = LayoutUtils.getLargeItemPadding(this);

		ImageView logo = new ImageView(this);
		logo.setPadding(pad, pad, pad, pad);
		logo.setImageResource(R.drawable.briar_logo_large);
		layout.addView(logo);

		setContentView(layout);
	}

	protected void startNextActivity() {
		long duration = System.currentTimeMillis() - start;
		if(LOG.isLoggable(INFO))
			LOG.info("Guice startup took " + duration + " ms");
		if(System.currentTimeMillis() >= EXPIRY_DATE) {
			if(LOG.isLoggable(INFO)) LOG.info("Expired");
			Intent i = new Intent(this, ExpiredActivity.class);
			i.setFlags(FLAG_ACTIVITY_NO_ANIMATION);
			startActivity(i);
		} else {
			Application app = getApplication();
			Injector guice = RoboGuice.getBaseApplicationInjector(app);
			if(guice.getInstance(DatabaseConfig.class).databaseExists()) {
				Intent i = new Intent(this, DashboardActivity.class);
				i.setFlags(FLAG_ACTIVITY_NEW_TASK);
				startActivity(i);
			} else {
				Intent i = new Intent(this, SetupActivity.class);
				i.setFlags(FLAG_ACTIVITY_NEW_TASK);
				startActivity(i);
			}
		}
	}
}
