package net.cubers.assaultcube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This activity is being launched on startup.
 * We need this activity because we want to show a progress text while we perform long running operations prior to launching the game.
 */
public class LaunchActivity extends Activity {

    // OUYA: the whole "Please wait..." splash is gated behind a single startup pipeline. onResume() can
    // fire more than once (transient focus/pause-resume at launch, dialog dismissal), and once the terms
    // are accepted every resume would otherwise kick off another exportAssets()+masterserver pipeline.
    // Guard it so the pipeline runs exactly once.
    private final AtomicBoolean startupBegun = new AtomicBoolean(false);
    // Guarantees startGame() runs exactly once, whether it comes from the pipeline finishing or the watchdog.
    private final AtomicBoolean gameStarted = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showTerms();
    }

    @SuppressLint("ApplySharedPref")
    private void showTerms() {
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean agreed = sharedPreferences.getBoolean(Constants.AGREEEMENTVERSION,false);
        if(agreed) {
            termsAccepted();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AcAlertDialogTheme);
            builder.setTitle("Agreement");
            String termsHtml = "Do you agree to the <a href=\"" + Constants.TERMSLINK
                    + "\">Terms and Conditions</a> and to the <a href=\"" + Constants.PRIVACYLINK + "\">Privacy Policy?</a>";
            // Html.fromHtml(String,int) is API 24+; OUYA is API 16 -> use the legacy single-arg overload.
            Spanned msg;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                msg = Html.fromHtml(termsHtml, Html.FROM_HTML_MODE_COMPACT);
            } else {
                msg = Html.fromHtml(termsHtml);
            }
            builder.setMessage(msg);
            builder.setCancelable(true);
            builder.setPositiveButton("Yes", (dialog, id) -> {
                dialog.cancel();
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(Constants.AGREEEMENTVERSION, true);
                editor.commit();
                termsAccepted();
            });
            builder.setNegativeButton("No", (dialog, id) -> {
                dialog.cancel();
                finish();
            });
            AlertDialog dialog = builder.create();
            dialog.show();

            // make links clickable
            ((TextView)dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private void termsAccepted() {
        if(!startupBegun.compareAndSet(false, true)) return; // run the startup pipeline exactly once
        startExportWatchdog();
        AsyncTask.execute(() -> {
            try {
                Log.i("launch", "termsAccepted: exporting assets...");
                exportAssets();
                Log.i("launch", "assets exported; updating from masterserver...");
                updateFromMasterserver();
                Log.i("launch", "masterserver step done; starting game.");
            } catch (Throwable t) {
                // Never let a failing startup step strand the user on "Please wait..." forever. Any
                // uncaught exception/Error here (e.g. getExternalFilesDir() momentarily null, an
                // unexpected IO/runtime error in asset export, OOM) would otherwise skip the
                // startGame() call below and freeze the splash. Log it and launch anyway - the game
                // can run with stale/partial data and surfaces its own errors in-engine.
                Log.e("launch", "startup step failed; launching game anyway.", t);
            } finally {
                startGameSafely();
            }
        });
    }

    /**
     * Launch the game exactly once, from whichever path gets there first (the pipeline's finally above, or
     * the stall watchdog below). Marshalled to the main thread because it touches the Activity.
     */
    private void startGameSafely() {
        if(!gameStarted.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).post(this::startGame);
    }

    /**
     * Safety net for the real failure mode behind the "Please wait..." hang: the asset export can block on
     * slow / Xposed-hooked flash I/O *without throwing*, so the try/finally above never runs and the splash
     * stays up forever. We poll AssetExporter's progress timestamp on the main thread; a legitimate
     * slow-but-progressing export keeps bumping it, but a wedged one does not - so if it stops advancing for
     * STALL_MS we give up waiting and launch the game anyway (better than an infinite splash).
     */
    private void startExportWatchdog() {
        final Handler h = new Handler(Looper.getMainLooper());
        final long STALL_MS = 12000;   // a single file copy should never take this long
        final long POLL_MS = 3000;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if(gameStarted.get()) return; // already on our way into the game
                long stalledFor = android.os.SystemClock.elapsedRealtime() - AssetExporter.sLastProgressMs;
                if(stalledFor > STALL_MS) {
                    Log.e("launch", "asset export made no progress for " + stalledFor + "ms; assuming it is wedged and launching the game anyway.");
                    startGameSafely();
                    return;
                }
                h.postDelayed(this, POLL_MS); // still progressing - keep watching
            }
        }, POLL_MS);
    }

    /**
     * Exports assets from APK into our writeable directory.
     * Unfortunately AssaultCube uses two different API's for file access: SDL_RW from libsdl2 and fopen from cstdio.
     * - libsdl2 targets the app's internal storage meaning the *readonly* APK data itself and this behavior is not easy to change.
     *   AssaultCube reads only few things using SDL_RW such as audio files and certain images. No write access is performed (!)
     * - cstdio can target any accessible path on the device the app has permissions to
     *   AssaultCube reads most of the things using fopen and it writes *ALL* things using fopen.
     *   Since we cannot easily get rid of the libsdl2 behavior our approach is to extract all data from the APK to the app's external
     *   directory which is writeable and then have all calls to stdio target this directory. We do this once on startup.
     */
    private void exportAssets() {
        AssetExporter assetExporter = new AssetExporter();
        boolean copyAssetsRequired = assetExporter.isAssetExportRequired(LaunchActivity.this);
        if(copyAssetsRequired) {
            boolean assetsExported = assetExporter.copyAssets(LaunchActivity.this);
            if( !assetsExported ) {
                // todo make user aware of the failure.
                //  fs: AlertDialog does not work at this stage (see updateFromMasterserver() comments too)
                Log.e("launch", "The assets were not exported completely.");
            }
        }
    }

    /**
     * Cheap way to get the official serverlist once per app startup.
     * AC mobile currently supports official severs only and therefore a simple serverlist file on the web suffices - no server registration capabilities currently needed.
     * We do this in Java world because currently the AC masterserver client only supports ENET or HTTP but not HTTPS -
     * and we want HTTPS so that we can host the rather static serverlist on an arbitrary webserver.
     * todo should be upgraded to a more robust solution.
     */
    private void updateFromMasterserver() {
        HttpURLConnection urlConnection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            File file = new File(LaunchActivity.this.getExternalFilesDir(null), Constants.SERVERLISTFILE);
            outputStream = new FileOutputStream(file);

            // write variables to the script
            // these are useful so that the serverlist can provide conditional actions depending on app version
            outputStream.write(("acos = \"android\"\n" ).getBytes("UTF-8"));
            outputStream.write(("acbuild = " + BuildConfig.VERSION_CODE + "\n").getBytes("UTF-8"));

            // write serverlist to the script
            try {
                URL url = new URL(Constants.SERVERLIST);
                urlConnection = (HttpURLConnection) url.openConnection();
                // OUYA may be offline/slow: never let this block "Please wait..." indefinitely.
                urlConnection.setConnectTimeout(5000);
                urlConnection.setReadTimeout(5000);
                inputStream = new BufferedInputStream(urlConnection.getInputStream());
            } catch(Exception ex) {
                // OUYA: the serverlist is hosted on raw.githubusercontent.com over HTTPS, whose modern TLS
                // the API-16 OpenSSL can't negotiate, so this ALWAYS fails on the Ouya. Fall back to the
                // serverlist that is bundled in the APK assets (config/mobile_serverlist.cfg) so the server
                // browser is still populated with the official servers offline.
                Log.e( "launch", "Update from Masterserver failed; falling back to bundled serverlist.", ex);
                try {
                    InputStream as = getAssets().open("config/mobile_serverlist.cfg");
                    byte[] b = new byte[1024]; int r;
                    while((r = as.read(b)) != -1) outputStream.write(b, 0, r);
                    as.close();
                    outputStream.write(("\ndelalias acos\n").getBytes("UTF-8"));
                    outputStream.write(("delalias acbuild\n").getBytes("UTF-8"));
                } catch(Exception e2) {
                    Log.e( "launch", "Bundled serverlist fallback failed.", e2);
                }
                return;
            }

            int read = 0;
            byte[] bytes = new byte[1024];
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }

            // clear vars
            outputStream.write(("\ndelalias acos\n").getBytes("UTF-8"));
            outputStream.write(("delalias acbuild\n").getBytes("UTF-8"));
        }
        catch (Exception e) {
            // ignore other errors and let the user run the game with old/stale serverlist
            e.printStackTrace();
        }
        finally {
            if(urlConnection != null) urlConnection.disconnect();
            if(inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) { }
            }
            if(outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Let the user decide whether or not to retry the masterserver update
     * fs: exception when e.g. http not https should've triggered this dialog from updateFromMasterserver()
     * >> E/WindowManager: android.view.WindowLeaked: Activity net.cubers.assaultcube.LaunchActivity has leaked window DecorView@a58d0c1[LaunchActivity] that was originally added here
     * >> […] pointing at line "dialog.show();"
     */
    private void showRetryUpdateFromMasterserver() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AcAlertDialogTheme);
        builder.setTitle("Something went wrong");
        builder.setMessage("Could not update the list of servers from the internet.");
        builder.setCancelable(true);
        builder.setPositiveButton("Retry", (dialog, id) -> {
            AsyncTask.execute(this::updateFromMasterserver);
        });
        builder.setNegativeButton("Ignore", (dialog, id) -> {
            dialog.cancel();
            startGame();
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void startGame() {
        Intent intent = new Intent(LaunchActivity.this, AssaultCubeActivity.class);
        startActivity(intent);
        finish();
    }
}