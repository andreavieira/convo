package a3.com.convo.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.GraphRequest;
import com.facebook.GraphResponse;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.LoginButton;
import com.parse.FindCallback;
import com.parse.LogInCallback;
import com.parse.ParseException;
import com.parse.ParseQuery;
import com.parse.ParseUser;
import com.parse.SaveCallback;
import com.parse.SignUpCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import a3.com.convo.Constants;
import a3.com.convo.Models.Page;
import a3.com.convo.R;

public class LoginActivity extends AppCompatActivity {

    private LoginButton loginButton;
    private CallbackManager callbackManager;
    private boolean onSuccessCalled;

    private static final String NAME = "name";
    private static final String ID = "id";
    private static final String OBJECT_ID = "objectId";
    private static final String PAGE_LIKES = "pageLikes";
    private static final String LIKES = "likes";
    private static final String CATEGORY = "category";
    private static final String COVER = "cover";
    private static final String SOURCE = "source";
    private static final String DATA = "data";
    private static final String PICTURE = "picture";
    private static final String URL = "url";
    private static final String FRIENDS = "friends";
    private static final String USERNAME = "username";
    private static final String EMAIL = "email";
    private static final String PASSWORD = "password";
    private static final String PROF_PIC_URL = "profPicUrl";
    private static final String OTHER_LIKES = "otherLikes";

    // maps Page IDs to Object IDs for quick lookup of duplicate pages
    private HashMap<String, String> existingPages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        // TODO fix this quick fix to on success being called twice
        // onSuccess for login is being called twice even though login button onClick is called once
        onSuccessCalled = false;

        final Context context = this;

        // populate the existing pages HashMap from the Parse server
        existingPages = new HashMap<>();
        ParseQuery<Page> query = ParseQuery.getQuery(Page.class);
        if (query == null) {
            Log.e("LoginActivity", "Query was null");
            return;
        }
        query.whereExists(OBJECT_ID);
        query.findInBackground(new FindCallback<Page>() {
            @Override
            public void done(List<Page> objects, ParseException e) {
                if (objects == null || objects.isEmpty()) {
                    // there are no pages in the parse server so hash map stays empty
                    Log.e("LoginActivity", "no pages in the server or query failed because objects was empty.");
                    return;
                }
                for (Page page : objects) {
                    if (page == null || page.getPageId() == null || page.getObjectId() == null) {
                        Log.e("LoginActivity", "Page is null");
                        return;
                    }
                    existingPages.put(page.getPageId(), page.getObjectId());
                }
            }
        });

        // check to see if the user is already logged in
        loginButton = (LoginButton) findViewById(R.id.login_button);
        callbackManager = CallbackManager.Factory.create();
        final AccessToken accessToken = AccessToken.getCurrentAccessToken();
        final boolean isLoggedIn = accessToken != null && !accessToken.isExpired();

        if (isLoggedIn) {
            ParseUser user = ParseUser.getCurrentUser();
            // if user logged into Facebook and Parse, then refresh their info and send them to the home screen
            if (user != null) {
                getLikedPageInfo(accessToken);
                getFriendsOnApp(accessToken);
                Intent i = new Intent(LoginActivity.this, HomeScreenActivity.class);
                startActivity(i);
                finish();
            }
            // if user logged into Facebook but not Parse, then get their info and log them into Parse
            else {
                getUserInfo(accessToken);
            }
        }

        // if user is not logged in/signed up to Facebook, the button shows up
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LoginManager lm = LoginManager.getInstance();
                if (lm == null) {
                    Log.e("LoginActivity", "LoginManager is null");
                    return;
                }
                lm.logInWithReadPermissions(LoginActivity.this,
                        Arrays.asList(Constants.USER_LIKES,
                                Constants.USER_FRIENDS,
                                Constants.EMAIL,
                                Constants.USER_HOMETOWN,
                                Constants.USER_LOCATION,
                                Constants.USER_TAGGED_PLACES));

            }
        });

        loginButton.registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
            @Override
            public void onSuccess(LoginResult loginResult) {
                if (!onSuccessCalled) {
                    Toast.makeText(context, "Logged in to Facebook!", Toast.LENGTH_LONG).show();
                    AccessToken at = loginResult.getAccessToken();
                    if (at == null) {
                        Log.e("LoginActivity", "AccessToken at was null.");
                        return;
                    }
                    getUserInfo(at);
                    Intent i = new Intent(LoginActivity.this, HomeScreenActivity.class);
                    startActivity(i);
                    onSuccessCalled = true;
                }
            }

            @Override
            public void onCancel() {
                Log.e("LoginActivity", "Facebook login cancelled");
            }

            @Override
            public void onError(FacebookException exception) {
                Log.e("LoginActivity", "Facebook login error: " + exception.toString());
                exception.printStackTrace();
            }
        });
    }

    // called when Facebook login returns
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        callbackManager.onActivityResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    // pulls a user's likes from the Graph API "likes" edge
    protected void getLikedPageInfo(AccessToken access_token) {
        GraphRequest data_request = GraphRequest.newMeRequest(
                access_token,
                new GraphRequest.GraphJSONObjectCallback() {
                    @Override
                    public void onCompleted(
                            JSONObject json_object,
                            GraphResponse response) {
                        final ParseUser user = ParseUser.getCurrentUser();
                        if (user == null) {
                            Log.e("LoginActivity", "The user was somehow automatically logged out of Parse after being logged in.");
                            return;
                        }
                        // initialize empty likes array
                        user.put(PAGE_LIKES, new ArrayList<String>());
                        if (json_object == null) {
                            // API request to facebook to fetch liked page info failed
                            Log.e("LoginActivity", "API Request to facebook for liked page info failed.");
                            return;
                        }
                        try {
                            // convert Json object into Json array
                            JSONObject likes_data = json_object.getJSONObject(LIKES);
                            if (likes_data == null) {
                                Log.e("LoginActivity", "likes_data is null.");
                                return;
                            }
                            JSONArray likes = likes_data.optJSONArray(DATA);
                            if (likes == null){
                                Log.e("LoginActivity", "User does not have page likes field.");
                                return;
                            }
                            for (int i = 0; i < likes.length(); i++) {
                                final JSONObject page = likes.optJSONObject(i);
                                if (page == null) {
                                    Log.e("LoginActivity", "The page fetched from facebook is null.");
                                    return;
                                }
                                String id = page.optString(ID);
                                if (id == null) {
                                    Log.e("LoginActivity", "page id in getLikedPageInfo is null");
                                    return;
                                }
                                if (existingPages.containsKey(id)) {
                                    // page already exists in Parse, so we just get the object id and add it to their likes array
                                    user.add(PAGE_LIKES, existingPages.get(id));
                                } else {
                                    // doesn't exist yet, so we add it to the server
                                    String category = page.optString(CATEGORY);
                                    if (category == null) {
                                        Log.e("LoginActivity", "Page category was null.");
                                        return;
                                    }
                                    String name = page.optString(NAME);
                                    if (name == null) {
                                        Log.e("LoginActivity", "Page name was null.");
                                        return;
                                    }
                                    JSONObject coverPicObject = page.getJSONObject(COVER);
                                    if (coverPicObject == null) {
                                        Log.e("LoginActivity", "coverPicObject was null.");
                                        return;
                                    }
                                    String coverUrl = coverPicObject.optString(SOURCE);
                                    if (coverUrl == null) {
                                        Log.e("LoginActivity", "Page coverUrl was null.");
                                        // no need to return since field is nullable
                                    }
                                    JSONObject profPicObject = page.getJSONObject(PICTURE);
                                    if (profPicObject == null) {
                                        Log.e("LoginActivity", "profPicObject was null.");
                                        return;
                                    }
                                    JSONObject profPicObjectData = profPicObject.getJSONObject(DATA);
                                    if (profPicObjectData == null) {
                                        Log.e("LoginActivity", "profPicObjectData was null.");
                                        return;
                                    }
                                    String profUrl = profPicObjectData.optString(URL);
                                    if (profUrl == null) {
                                        Log.e("LoginActivity", "Page prof was null.");
                                        // no need to return since field is nullable
                                    }
                                    final Page newPage = Page.newInstance(id, name, profUrl, coverUrl, category);
                                    newPage.saveInBackground(new SaveCallback() {
                                        @Override
                                        public void done(ParseException e) {
                                            if (e == null) {
                                                Log.e("LoginActivity", "Create page success");
                                                user.add(PAGE_LIKES, newPage.getObjectId());
                                                user.saveInBackground();
                                            } else {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });

        Bundle permission_param = new Bundle();
        // add fields to get the details of liked pages
        permission_param.putString(Constants.FIELDS, Constants.GET_LIKES_FIELDS);
        // grab more than 25 pages
        permission_param.putString(Constants.LIMIT, Integer.toString(Constants.LIKES_LIMIT));
        data_request.setParameters(permission_param);
        data_request.executeAsync();
    }

    protected void getFriendsOnApp(AccessToken access_token) {
        GraphRequest request = GraphRequest.newMyFriendsRequest(
                access_token,
                new GraphRequest.GraphJSONArrayCallback() {
                    @Override
                    public void onCompleted(JSONArray friends, GraphResponse response) {
                        try {
                            final ParseUser user = ParseUser.getCurrentUser();
                            if (user == null) {
                                Log.e("LoginActivity", "The user was somehow automatically logged out of Parse after being logged in.");
                                return;
                            }
                            // initialize empty friends array
                            user.put(FRIENDS, new ArrayList<String>());
                            for (int i = 0; i < friends.length(); i++) {
                                JSONObject friend = friends.optJSONObject(i);
                                if (friend == null) {
                                    Log.e("LoginActivity", "Friend object in getFriendsOnApp is null.");
                                    return;
                                }
                                final String id = friend.optString(ID);
                                if (id == null) {
                                    Log.e("LoginActivity", "Page coverUrl was null.");
                                    return;
                                }
                                ParseQuery<ParseUser> query = ParseUser.getQuery();
                                if (query == null) {
                                    // query was null, so maybe app should just crash?
                                    Log.e("LoginActivity", "Query was null in getFriendsOnApp");
                                    return;
                                }
                                query.whereEqualTo(USERNAME, id);
                                query.findInBackground(new FindCallback<ParseUser>() {
                                    @Override
                                    public void done(List<ParseUser> objects, ParseException e) {
                                        if (objects == null || objects.isEmpty()) {
                                            // if the user has a friend on the app that is for some
                                            // reason not on the server as well, skip adding that
                                            // friend and continue
                                            Log.e("LoginActivity", "Friend with facebook id " + id + " could not be found on Parse server.");
                                        }
                                        else {
                                            if (objects.size() != 1) {
                                                Log.e("LoginActivity", "There are multiple users on the parse server with facebook id " + id + ".");
                                                // TODO define way to flag/delete the duplicate users
                                                // which would never be created in the first place because we check server before creating new user
                                            }
                                            // get the friend ParseUser with the username matching the friend of current user
                                            ParseUser friend = objects.get(0);
                                            if (friend == null) {
                                                // friend was null
                                                Log.e("LoginActivity", "Parse query returned an array with a null user.");
                                                return;
                                            }
                                            String objectId = friend.getObjectId();
                                            if (objectId == null) {
                                                // friend was null
                                                Log.e("LoginActivity", "In getFriendsOnApp, object id of this friend failed to be found");
                                                return;
                                            }
                                            user.add(FRIENDS, objectId);
                                            user.saveInBackground();

                                        }
                                    }
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
        request.executeAsync();
    }

    protected void getUserInfo(final AccessToken access_token) {
        GraphRequest request = GraphRequest.newMeRequest(
                access_token,
                new GraphRequest.GraphJSONObjectCallback() {
                    @Override
                    public void onCompleted(JSONObject object, GraphResponse response) {
                        if (object == null) {
                            // API request to facebook to fetch user info failed
                            Log.e("LoginActivity", "API Request to facebook for user info failed.");
                            return;
                        }
                        try {
                            final String id = object.getString(ID);
                            if (id == null) {
                                Log.e("LoginActivity", "facebook id of the user object is null");
                                return;
                            }
                            final String email = object.getString(EMAIL);
                            if (email == null) {
                                Log.e("LoginActivity", "email field of the user object is null");
                                // no need to return, pass email anyway because field is nullable
                            }
                            final String name = object.getString(NAME);
                            if (name == null) {
                                Log.e("LoginActivity", "name of the user object is null (name is mandatory).");
                                return;
                            }
                            JSONObject picture = object.getJSONObject(PICTURE);
                            if (picture == null) {
                                Log.e("LoginActivity", "profile picture object is null.");
                                return;
                            }
                            JSONObject pic_data = picture.getJSONObject(DATA);
                            if (pic_data == null) {
                                Log.e("LoginActivity", "profile picture data object is null.");
                                return;
                            }
                            final String profPicUrl = pic_data.optString(URL);
                            if (email == null) {
                                Log.e("LoginActivity", "prof pic from the user object is null");
                                // no need to return, pass email anyway because field is nullable
                            }
                            ParseQuery<ParseUser> query = ParseUser.getQuery();
                            if (query == null) {
                                // query was null, so maybe app should just crash?
                                Log.e("LoginActivity", "Query was null in getUserInfo()");
                                return;
                            }
                            query.whereEqualTo(USERNAME, id);
                            query.findInBackground(new FindCallback<ParseUser>() {
                                @Override
                                public void done(List<ParseUser> objects, ParseException e) {
                                    if (e == null) {
                                        if (objects == null) {
                                            Log.e("LoginActivity", "Query returned null objects list in getUserInfo()");
                                            return;
                                        }
                                        // if the user doesn't exist
                                        if (objects.isEmpty()) {
                                            signUpNewUser(id, email, name, profPicUrl, access_token);
                                        }
                                        // if they're already in our server, update name and pic because these can change
                                        else {
                                            logInUser(id, name, profPicUrl, access_token);
                                        }
                                    }
                                    else {
                                        e.printStackTrace();
                                    }
                                }
                            });
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });
        Bundle permission_param = new Bundle();
        permission_param.putString(Constants.FIELDS, Constants.GET_USER_FIELDS);
        request.setParameters(permission_param);
        request.executeAsync();
    }

    protected void signUpNewUser(String id, @Nullable String email, String name, final String profPicUrl, final AccessToken access_token) {
        // Create the ParseUser
        ParseUser user = new ParseUser();
        // Set core properties
        user.setUsername(id);
        if (email != null) {
            user.setEmail(email);
        }
        user.setPassword(PASSWORD);
        user.put(NAME, name);
        user.put(PROF_PIC_URL, profPicUrl);
        user.put(OTHER_LIKES, new ArrayList<String>());
        // Invoke signUpInBackground
        user.signUpInBackground(new SignUpCallback() {
            public void done(ParseException e) {
                if (e == null) {
                    Toast.makeText(LoginActivity.this, "Signed up (Parse)!", Toast.LENGTH_LONG).show();
                    getLikedPageInfo(access_token);
                    getFriendsOnApp(access_token);
                } else {
                    Toast.makeText(LoginActivity.this, "Username taken or some other issue!", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    protected void logInUser(String id, final String name, final String profPicUrl, final AccessToken access_token) {
        ParseUser.logInInBackground(id, PASSWORD, new LogInCallback() {
            public void done(ParseUser user, ParseException e) {
                if (user != null) {
                    Toast.makeText(LoginActivity.this, "Logged in!", Toast.LENGTH_LONG).show();
                    user.put(Constants.NAME, name);
                    user.put(Constants.PROF_PIC_URL, profPicUrl);
                    getLikedPageInfo(access_token);
                    getFriendsOnApp(access_token);
                } else {
                    Toast.makeText(LoginActivity.this, "Failed login (Parse)", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    public void getTaggedPlaces(final AccessToken access_token) {
        GraphRequest data_request = GraphRequest.newMeRequest(
                access_token,
                new GraphRequest.GraphJSONObjectCallback() {
                    @Override
                    public void onCompleted(
                            JSONObject json_object,
                            GraphResponse response) {
                        final ParseUser user = ParseUser.getCurrentUser();
                        if (user == null) {
                            Log.e("Login getTaggedPlaces()", "The user was somehow automatically logged out of Parse after being logged in.");
                            return;
                        }
                        // initialize empty likes array
                        user.put("taggedPlaces", new ArrayList<String>());
                        if (json_object == null) {
                            // for some reason, API request to facebook to fetch tagged places
                            // failed, continue without updated information.
                            Log.e("LoginActivity", "API Request to facebook for tagged places failed.");
                            return;
                        }
                            try {
                                // get all the tagged places
                                // convert Json object into Json array
                                JSONObject taggedPlacesData = json_object.getJSONObject("tagged_places");
                                if (taggedPlacesData == null) {
                                    Log.e("LoginActivity", "Query returned null for tagged places.");
                                    return;
                                }
                                JSONArray taggedPlaces = taggedPlacesData.optJSONArray(DATA);
                                if (taggedPlaces == null) {
                                    Log.e("LoginActivity", "Tagged places array has no data");
                                    return;
                                }
                                for (int i = 0; i < taggedPlaces.length(); i++) {
                                    final JSONObject place = taggedPlaces.optJSONObject(i);
                                    if (place == null) {
                                        Log.e("LoginActivity", "place in tagged places array has no data");
                                        return;
                                    }
                                    String id = place.optString(ID);
                                    if (id == null) {
                                        Log.e("LoginActivity", "id in place in tagged places array is null");
                                        return;
                                    }

                                    if (existingPages.containsKey(id)) {
                                        // page already exists in Parse, so we just get the object id and add it to their likes array
                                        user.add("taggedPlaces", existingPages.get(id));
                                    } else {
                                        // doesn't exist yet, so we add it to the server
                                        String name = place.optString(NAME);
                                        if (name == null) {
                                            Log.e("LoginActivity", "name of place in tagged places array is null");
                                            return;
                                        }
                                        final Page newPlacePage = Page.newInstance(id, name, null, null, null);
                                        newPlacePage.saveInBackground(new SaveCallback() {
                                            @Override
                                            public void done(ParseException e) {
                                                if (e == null) {
                                                    Log.e("LoginActivity", "Create page success");
                                                    user.add("taggedPlaces", newPlacePage.getObjectId());
                                                    user.saveInBackground();
                                                } else {
                                                    e.printStackTrace();
                                                }
                                            }
                                        });
                                    }
                                }
                                // finished getting all the tagged places
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                    }
                });
        Bundle permission_param = new Bundle();
        permission_param.putString("fields", "tagged_places");
        data_request.setParameters(permission_param);
        data_request.executeAsync();
    }
    public void getHometown(final AccessToken access_token) {
        GraphRequest data_request = GraphRequest.newMeRequest(
                access_token,
                new GraphRequest.GraphJSONObjectCallback() {
                    @Override
                    public void onCompleted(
                            JSONObject json_object,
                            GraphResponse response) {
                        final ParseUser user = ParseUser.getCurrentUser();
                        if (user == null) {
                            Log.e("Login getHometown()", "The user was somehow automatically logged out of Parse after being logged in.");
                            return;
                        }
                        if (json_object == null) {
                            // for some reason, API request to facebook to fetch hometown
                            // failed, continue without updated information.
                            Log.e("LoginActivity", "API Request to facebook for hometown failed.");
                        }
                            try {
                                // get hometown
                                JSONObject hometown = json_object.getJSONObject("hometown");
                                if (hometown == null) {
                                    Log.e("Login getHometown()", "object hometown is null.");
                                    return;
                                }
                                String home_id = hometown.optString(ID);
                                if (home_id == null) {
                                    Log.e("Login getHometown()", "object hometown's id is null.");
                                    return;
                                }
                                if (existingPages.containsKey(home_id)) {
                                    // page already exists in Parse, so we just get the object id and add it to their likes array
                                    user.add("hometown", existingPages.get(home_id));
                                } else {
                                    // doesn't exist yet, so we add it to the server
                                    String name = hometown.optString(NAME);
                                    if (name == null) {
                                        Log.e("Login getHometown()", "object hometown's name is null.");
                                        return;
                                    }
                                    final Page newHometownPage = Page.newInstance(home_id, name, null, null, null);

                                    newHometownPage.saveInBackground(new SaveCallback() {
                                        @Override
                                        public void done(ParseException e) {
                                            if (e == null) {
                                                Log.e("LoginActivity", "Create page success");
                                                user.put("hometown", newHometownPage.getObjectId());
                                                user.saveInBackground();
                                            } else {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                }
                                // finished getting hometown
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                    }
                });
        Bundle permission_param = new Bundle();
        permission_param.putString("fields", "hometown");
        data_request.setParameters(permission_param);
        data_request.executeAsync();
    }

    public void getCurrentLocation(final AccessToken access_token) {
        GraphRequest data_request = GraphRequest.newMeRequest(
                access_token,
                new GraphRequest.GraphJSONObjectCallback() {
                    @Override
                    public void onCompleted(
                            JSONObject json_object,
                            GraphResponse response) {
                        final ParseUser user = ParseUser.getCurrentUser();
                        if (user == null) {
                            Log.e("Login getLocation()", "The user was somehow automatically logged out of Parse after being logged in.");
                            return;
                        }
                        if (json_object == null) {
                            // for some reason, API request to facebook to fetch location
                            // failed, continue without updated information.
                            Log.e("LoginActivity", "API Request to facebook for location failed.");
                            return;
                        }
                            try {
                                // get location
                                JSONObject location = json_object.getJSONObject("location");
                                String location_id = location.optString(ID);

                                if (existingPages.containsKey(location_id)) {
                                    // page already exists in Parse, so we just get the object id and add it to their likes array
                                    user.put("location", existingPages.get(location_id));
                                } else {
                                    // doesn't exist yet, so we add it to the server
                                    String name = location.optString(NAME);
                                    final Page newLocationPage = Page.newInstance(location_id, name, null, null, null);

                                    newLocationPage.saveInBackground(new SaveCallback() {

                                        @Override
                                        public void done(ParseException e) {
                                            if (e == null) {
                                                Log.e("LoginActivity", "Create page success");
                                                user.add("location", newLocationPage.getObjectId());
                                                user.saveInBackground();
                                            } else {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                }

                                // finished getting location
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                    }
                });
        Bundle permission_param = new Bundle();
        permission_param.putString("fields", "location");
        data_request.setParameters(permission_param);
        data_request.executeAsync();
    }

}
