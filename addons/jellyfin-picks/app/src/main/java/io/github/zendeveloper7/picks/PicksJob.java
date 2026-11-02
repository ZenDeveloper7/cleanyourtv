package io.github.zendeveloper7.picks;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Every hour, while the TV is on and on the network: new picks for the moment (the time of day, the weather and
 * anything watched since). Persisted, so it survives a restart. Does nothing before the setup is done.
 */
public final class PicksJob extends JobService {

    private static final int JOB_ID = 0x504B;   // "PK"
    private static final long PERIOD_MS = 60 * 60_000L;

    /** Schedules the job once; an already scheduled one keeps its timing. */
    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, PicksJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Refresh.configured(this)) return false;
        Refresh.async(this, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
