package me.vickychijwani.spectre.network;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;

import me.vickychijwani.spectre.model.entity.AuthToken;
import me.vickychijwani.spectre.model.entity.Role;
import me.vickychijwani.spectre.model.entity.User;
import me.vickychijwani.spectre.network.entity.AuthReqBody;
import me.vickychijwani.spectre.network.entity.RefreshReqBody;
import me.vickychijwani.spectre.network.entity.RevokeReqBody;
import me.vickychijwani.spectre.network.entity.UserList;
import me.vickychijwani.spectre.util.NetworkUtils;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import rx.functions.Action2;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * PURPOSE: contract tests for the Ghost API
 *
 * Run these to detect ANY behaviour changes (breaking or non-breaking) in the
 * API when a new Ghost version comes out. These are integration-style tests
 * that run against an actual Ghost instance.
 */

// ignored by default since these tests require a running Ghost instance so they can't run in CI yet
// remove @Ignore and run this locally when needed
@Ignore
public final class GhostApiTest {

    // TODO set up a fixture with npm install ghost and some sql to make this portable
    private static final String BLOG_URL = "http://localhost:2368/";
    private static final String TEST_USER = "vickychijwani@gmail.com";
    private static final String TEST_PWD = "ghosttest";

    private GhostApiService api;

    @Before
    public void setupApiService() {
        String baseUrl = NetworkUtils.makeAbsoluteUrl(BLOG_URL, "ghost/api/v0.1/");
        OkHttpClient httpClient = new ProductionHttpClientFactory().create(null)
                .newBuilder()
                .addInterceptor(new HttpLoggingInterceptor()
                        .setLevel(HttpLoggingInterceptor.Level.BODY))
                .build();
        Retrofit retrofit = GhostApiUtils.getRetrofit(baseUrl, httpClient);
        api = retrofit.create(GhostApiService.class);
    }

    @After
    public void destroyApiService() {
        api = null;
    }

    @Test
    public void test_getClientSecret() {
        String clientSecret = getClientSecret(api);

        // must NOT be null since that's only possible with a very old Ghost version (< 0.7.x)
        assertThat(clientSecret, notNullValue());
        // Ghost uses a 12-character client secret, evident from the Ghost source code (1 byte can hold 2 hex chars):
        // { secret: crypto.randomBytes(6).toString('hex') }
        // file: core/server/data/migration/fixtures/004/04-update-ghost-admin-client.js
        assertThat(clientSecret.length(), is(12));
    }

    @Test
    public void test_getAuthToken_withPassword() {
        doWithAuthToken(api, (authToken, response) -> {
            assertThat(response.code(), is(HTTP_OK));
            assertThat(authToken.getTokenType(), is("Bearer"));
            assertThat(authToken.getAccessToken(), notNullValue());
            assertThat(authToken.getRefreshToken(), notNullValue());
            assertThat(authToken.getExpiresIn(), is(3600));
        });
    }

    @Test
    public void test_getAuthToken_withRefreshToken() {
        doWithAuthToken(api, (expiredToken, __) -> {
            String clientSecret = getClientSecret(api);
            RefreshReqBody credentials = new RefreshReqBody(expiredToken.getRefreshToken(),
                    clientSecret);
            Response<AuthToken> response = execute(api.refreshAuthToken(credentials));
            AuthToken refreshedToken = response.body();

            assertThat(response.code(), is(HTTP_OK));
            assertThat(refreshedToken.getTokenType(), is("Bearer"));
            assertThat(refreshedToken.getAccessToken(), notNullValue());
            assertThat(refreshedToken.getRefreshToken(), isEmptyOrNullString());
            assertThat(refreshedToken.getExpiresIn(), is(3600));

            RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, refreshedToken.getRefreshToken(), clientSecret),
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, refreshedToken.getAccessToken(), clientSecret)
            };
            for (RevokeReqBody reqBody : revokeReqs) {
                execute(api.revokeAuthToken(refreshedToken.getAuthHeader(), reqBody));
            }
        });
    }

    @Test
    public void test_revokeAuthToken() {
        String clientSecret = getClientSecret(api);
        AuthReqBody credentials = new AuthReqBody(TEST_USER, TEST_PWD, clientSecret);
        AuthToken authToken = execute(api.getAuthToken(credentials)).body();

        RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, authToken.getRefreshToken(), clientSecret),
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, authToken.getAccessToken(), clientSecret)
        };
        for (RevokeReqBody reqBody : revokeReqs) {
            Response<JsonElement> response = execute(api.revokeAuthToken(authToken.getAuthHeader(), reqBody));
            JsonElement jsonResponse = response.body();
            JsonObject jsonObj = jsonResponse.getAsJsonObject();

            assertThat(response.code(), is(HTTP_OK));
            assertThat(jsonObj.has("error"), is(false));
            assertThat(jsonObj.get("token").getAsString(), is(reqBody.token));
        }
    }

    @Test
    public void test_getCurrentUser() {
        doWithAuthToken(api, (authToken, __) -> {
            Response<UserList> response = execute(api.getCurrentUser(authToken.getAuthHeader(), ""));
            UserList users = response.body();
            User user = users.users.get(0);

            assertThat(response.code(), is(HTTP_OK));
            assertThat(user, notNullValue());
            //assertThat(user.getId(), instanceOf(Integer.class)); // no-op, int can't be null
            assertThat(user.getUuid(), notNullValue());
            assertThat(user.getName(), notNullValue());
            assertThat(user.getSlug(), notNullValue());
            assertThat(user.getEmail(), is(TEST_USER));
            //assertThat(user.getImage(), anyOf(nullValue(), notNullValue())); // no-op
            //assertThat(user.getBio(), anyOf(nullValue(), notNullValue())); // no-op
            assertThat(user.getRoles(), not(empty()));

            Role role = user.getRoles().first();
            //assertThat(role.getId(), instanceOf(Integer.class)); // no-op, int can't be null
            assertThat(role.getUuid(), notNullValue());
            assertThat(role.getName(), notNullValue());
            assertThat(role.getDescription(), notNullValue());
        });
    }



    // private helpers
    private static void doWithAuthToken(GhostApiService api,
                                        Action2<AuthToken, Response<AuthToken>> callback) {
        String clientSecret = getClientSecret(api);
        AuthReqBody credentials = new AuthReqBody(TEST_USER, TEST_PWD, clientSecret);
        Response<AuthToken> response = execute(api.getAuthToken(credentials));
        AuthToken authToken = response.body();
        callback.call(authToken, response);
        RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, authToken.getRefreshToken(), clientSecret),
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, authToken.getAccessToken(), clientSecret)
        };
        for (RevokeReqBody reqBody : revokeReqs) {
            execute(api.revokeAuthToken(authToken.getAuthHeader(), reqBody));
        }
    }

    @Nullable
    private static String getClientSecret(GhostApiService api) {
        String html = execute(api.getLoginPage(NetworkUtils.makeAbsoluteUrl(BLOG_URL, "ghost/"))).body();
        return GhostApiUtils.extractClientSecretFromHtml(html);
    }

    @NonNull
    private static <T> Response<T> execute(Call<T> call) {
        // intentionally swallows the IOException to make test code cleaner
        try {
            return call.execute();
        } catch (IOException e) {
            e.printStackTrace();
            // suppress leaking knowledge of this null to hide false-positive errors by IntelliJ
            //noinspection ConstantConditions
            return null;
        }
    }

}
