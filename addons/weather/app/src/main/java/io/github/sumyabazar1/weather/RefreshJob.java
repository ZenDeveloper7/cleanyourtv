package io.github.sumyabazar1.weather;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Every 30 minutes, reads the current weather for the chosen place (if there is one) and saves it to NowStore for
 * the launcher. Persisted across restarts (RECEIVE_BOOT_COMPLETED), and only on a network.
 */
public final class RefreshJob extends JobService {
    private static final int JOB_ID = 0x5745;   // "WE"
    private static final long PERIOD_MS = 30 * 60_000L;

    /** Schedules the periodic job. Does nothing when it is already scheduled, so the timer is not reset. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, RefreshJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        SharedPreferences place = getSharedPreferences(WeatherActivity.PREFS, MODE_PRIVATE);
        if (!place.contains("name")) return false;   // no city chosen yet: nothing to read
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        new Thread(() -> {
            boolean failed = false;
            try {
                NowStore.save(this, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception e) {
                failed = true;
            }
            jobFinished(params, failed);
        }, "weather-refresh").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // stopped by the system (e.g. network lost): try again
    }
}
