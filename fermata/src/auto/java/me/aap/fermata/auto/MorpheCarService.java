package me.aap.fermata.auto;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/**
 * The "Morphe YouTube" app in Android Auto, see MorpheCarActivity.
 */
public class MorpheCarService extends CarActivityService {

	@Override
	public Class<? extends CarActivity> getCarActivity() {
		return MorpheCarActivity.class;
	}
}
