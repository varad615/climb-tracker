package fr.steren.climbtracker;

import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.firebase.client.AuthData;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.plus.Plus;

import java.io.IOException;
import java.util.Date;


/**
 * An activity representing a list of ClimbSessions. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link ClimbSessionDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 * <p/>
 * The activity makes heavy use of fragments. The list of items is a
 * {@link ClimbSessionListFragment} and the item details
 * (if present) is a {@link ClimbSessionDetailFragment}.
 * <p/>
 * This activity also implements the required
 * {@link ClimbSessionListFragment.Callbacks} interface
 * to listen for item selections.
 */
public class ClimbTracker extends AppCompatActivity
        implements ClimbSessionListFragment.Callbacks, GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        GradePickerFragment.GradeDialogListener {

    private static final String LOG_TAG = ClimbTracker.class.getSimpleName();

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */
    private boolean mTwoPane;

    private Firebase firebaseRef;

    /** reference to the potential last added climb */
    private Firebase mNewClimbRef;

    /* Client used to interact with Google APIs. */
    private GoogleApiClient mGoogleApiClient;

    /* Data from the authenticated user */
    private AuthData mAuthData;

    /* A flag indicating that a PendingIntent is in progress and prevents
     * us from starting further intents.
     */
    private boolean mIntentInProgress;

    /* Request code used to invoke sign in user interactions. */
    private static final int RC_SIGN_IN = 0;

    private static final String PREF_GRAD_SYSTEM_TYPE = "pref_gradeSystemType";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_tracker);

        Firebase.setAndroidContext(this);
        firebaseRef = new Firebase(getResources().getString(R.string.firebase_url));

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Plus.API)
                .addScope(new Scope(Scopes.PROFILE))
                .build();

        /* Check if the user is authenticated with Firebase already. If this is the case we can set the authenticated
         * user and hide hide any login buttons */
        firebaseRef.addAuthStateListener(new Firebase.AuthStateListener() {
            @Override
            public void onAuthStateChanged(AuthData authData) {
                setAuthenticatedUser(authData);
            }
        });


        AuthData authData = firebaseRef.getAuth();
        if(authData == null) {
            mGoogleApiClient.connect();
        }

        if (findViewById(R.id.climbsession_detail_container) != null) {
            // The detail container view will be present only in the
            // large-screen layouts (res/values-large and
            // res/values-sw600dp). If this view is present, then the
            // activity should be in two-pane mode.
            mTwoPane = true;

            // In two-pane mode, list items should be given the
            // 'activated' state when touched.
            ((ClimbSessionListFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.climbsession_list))
                    .setActivateOnItemClick(true);
        }

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogFragment gradePickerFragment = new GradePickerFragment();
                gradePickerFragment.show(getSupportFragmentManager(), "gradePicker");
            }
        });


        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        CheckPreferenceAndSendToWear();
    }

    private void CheckPreferenceAndSendToWear() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String gradeSystemTypePref = sharedPref.getString(PREF_GRAD_SYSTEM_TYPE, "uuia");

        // TODO Send the preference to the watch
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(ClimbTracker.this, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_disconnect) {
            firebaseRef.unauth();
            mGoogleApiClient.disconnect();
            mGoogleApiClient.connect();
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Once a user is logged in, take the mAuthData provided from Firebase and "use" it.
     */
    private void setAuthenticatedUser(AuthData authData) {
        if (authData != null) {
            String name = (String) authData.getProviderData().get("displayName");
            Toast.makeText(this, "Authenticated user: " + name, Toast.LENGTH_LONG).show();
        }
        this.mAuthData = authData;
    }

    public void onConnectionFailed(ConnectionResult result) {
        if (!mIntentInProgress && result.hasResolution()) {
            try {
                mIntentInProgress = true;
                startIntentSenderForResult(result.getResolution().getIntentSender(),
                        RC_SIGN_IN, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                // The intent was canceled before it was sent.  Return to the default
                // state and attempt to connect to get an updated ConnectionResult.
                mIntentInProgress = false;
                mGoogleApiClient.connect();
            }
        }
    }

    protected void onActivityResult(int requestCode, int responseCode, Intent intent) {
        if (requestCode == RC_SIGN_IN) {
            mIntentInProgress = false;
            if (!mGoogleApiClient.isConnecting()) {
                mGoogleApiClient.connect();
            }
        }
    }

    private void getGoogleOAuthTokenAndLogin() {
        /* Get OAuth token in Background */
        AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
            String errorMessage = null;

            @Override
            protected String doInBackground(Void... params) {
                String token = null;

                try {
                    String scope = String.format("oauth2:%s", Scopes.PROFILE);
                    token = GoogleAuthUtil.getToken(ClimbTracker.this, Plus.AccountApi.getAccountName(mGoogleApiClient), scope);
                } catch (IOException transientEx) {
                    /* Network or server error */
                    Log.e(LOG_TAG, "Error authenticating with Google: " + transientEx);
                    errorMessage = "Network error: " + transientEx.getMessage();
                } catch (UserRecoverableAuthException e) {
                    Log.w(LOG_TAG, "Recoverable Google OAuth error: " + e.toString());
                    /* We probably need to ask for permissions, so start the intent if there is none pending */
                    mGoogleApiClient.connect();
                } catch (GoogleAuthException authEx) {
                    /* The call is not ever expected to succeed assuming you have already verified that
                     * Google Play services is installed. */
                    Log.e(LOG_TAG, "Error authenticating with Google: " + authEx.getMessage(), authEx);
                    errorMessage = "Error authenticating with Google: " + authEx.getMessage();
                }

                return token;
            }

            @Override
            protected void onPostExecute(String token) {
                // Authenticate
                // see https://www.firebase.com/docs/android/guide/login/google.html
                firebaseRef.authWithOAuthToken("google", token, new Firebase.AuthResultHandler() {
                    @Override
                    public void onAuthenticated(AuthData authData) {
                        // the Google user is now authenticated with Firebase
                        setAuthenticatedUser(authData);
                    }

                    @Override
                    public void onAuthenticationError(FirebaseError firebaseError) {
                        Toast.makeText(ClimbTracker.this, firebaseError.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
        task.execute();
    }

    public void onConnected(Bundle connectionHint) {
        getGoogleOAuthTokenAndLogin();
    }

    public void onConnectionSuspended(int cause) {
        mGoogleApiClient.connect();
    }


    /**
     * Callback method from {@link ClimbSessionListFragment.Callbacks}
     * indicating that the item was selected.
     */
    @Override
    public void onItemSelected(Climb climb) {
        if (mTwoPane) {
            // In two-pane mode, show the detail view in this activity by
            // adding or replacing the detail fragment using a
            // fragment transaction.
            Bundle arguments = new Bundle();
            arguments.putString(ClimbSessionDetailFragment.ARG_CLIMB_ID, climb.id);
            arguments.putLong(ClimbSessionDetailFragment.ARG_CLIMB_TIME, climb.getDate().getTime());
            ClimbSessionDetailFragment fragment = new ClimbSessionDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.climbsession_detail_container, fragment)
                    .commit();

        } else {
            // In single-pane mode, simply start the detail activity
            // for the selected item ID.
            Intent detailIntent = new Intent(this, ClimbSessionDetailActivity.class);
            detailIntent.putExtra(ClimbSessionDetailFragment.ARG_CLIMB_ID, climb.id);
            detailIntent.putExtra(ClimbSessionDetailFragment.ARG_CLIMB_TIME, climb.getDate().getTime());
            startActivity(detailIntent);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        CheckPreferenceAndSendToWear();
    }

    @Override
    public void onGradeSelected(String grade) {
        Climb newClimb = new Climb(new Date(), grade);
        mNewClimbRef = firebaseRef.child("users")
                .child(mAuthData.getUid())
                .child("climbs")
                .push();
        mNewClimbRef.setValue(newClimb);

        final CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.mainLayout);
        Snackbar.make(coordinatorLayout, R.string.climb_confirmation, Snackbar.LENGTH_LONG).setAction(R.string.climb_save_undo, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mNewClimbRef.removeValue();
            }
        }).show();
    }
}
