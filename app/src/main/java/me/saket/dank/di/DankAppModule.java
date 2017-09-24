package me.saket.dank.di;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.preference.PreferenceManager;

import com.danikula.videocache.HttpProxyCacheServer;
import com.f2prateek.rx.preferences2.RxSharedPreferences;
import com.facebook.stetho.okhttp3.StethoInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytimes.android.external.fs3.filesystem.FileSystem;
import com.nytimes.android.external.fs3.filesystem.FileSystemFactory;
import com.squareup.moshi.Moshi;
import com.squareup.sqlbrite2.BriteDatabase;
import com.squareup.sqlbrite2.SqlBrite;

import net.dean.jraw.RedditClient;
import net.dean.jraw.auth.AuthenticationManager;
import net.dean.jraw.http.LoggingMode;
import net.dean.jraw.http.UserAgent;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.reactivex.schedulers.Schedulers;
import me.saket.dank.BuildConfig;
import me.saket.dank.R;
import me.saket.dank.data.DankRedditClient;
import me.saket.dank.data.DankSqliteOpenHelper;
import me.saket.dank.data.ErrorResolver;
import me.saket.dank.data.ImgurRepository;
import me.saket.dank.data.InboxManager;
import me.saket.dank.data.SharedPrefsManager;
import me.saket.dank.data.VotingManager;
import me.saket.dank.notifs.MessagesNotificationManager;
import me.saket.dank.ui.submission.CommentsManager;
import me.saket.dank.ui.user.UserSession;
import me.saket.dank.utils.AutoValueMoshiAdapterFactory;
import me.saket.dank.utils.JacksonHelper;
import me.saket.dank.utils.MoshiAccountAdapter;
import me.saket.dank.utils.MoshiMessageAdapter;
import me.saket.dank.utils.MoshiSubmissionAdapter;
import me.saket.dank.utils.OkHttpWholesomeAuthIntercepter;
import me.saket.dank.utils.StreamableRepository;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.moshi.MoshiConverterFactory;
import timber.log.Timber;

@Module
public class DankAppModule {

  public static final int NETWORK_CONNECT_TIMEOUT_SECONDS = 15;
  public static final int NETWORK_READ_TIMEOUT_SECONDS = 10;
  private Application appContext;

  public DankAppModule(Application appContext) {
    this.appContext = appContext;
  }

  @Provides
  Application provideAppContext() {
    return appContext;
  }

  @Provides
  UserAgent provideRedditUserAgent() {
    try {
      PackageInfo packageInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
      return UserAgent.of("android", appContext.getPackageName(), packageInfo.versionName, "saketme");

    } catch (PackageManager.NameNotFoundException e) {
      throw new IllegalStateException("Couldn't get app version name");
    }
  }

  @Provides
  @Singleton
  RedditClient provideRedditClient(UserAgent redditUserAgent) {
    RedditClient redditClient = new RedditClient(redditUserAgent);
    redditClient.setLoggingMode(LoggingMode.ALWAYS);
    redditClient.getHttpAdapter().setConnectTimeout(NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    redditClient.getHttpAdapter().setReadTimeout(NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return redditClient;
  }

  // Already singleton.
  @Provides
  AuthenticationManager provideRedditAuthManager() {
    return AuthenticationManager.get();
  }

  @Provides
  @Singleton
  DankRedditClient provideDankRedditClient(RedditClient redditClient, AuthenticationManager authManager, UserSession userSession) {
    return new DankRedditClient(appContext, redditClient, authManager, userSession);
  }

  @Provides
  @Singleton
  InboxManager provideInboxManager(DankRedditClient dankRedditClient, BriteDatabase briteDatabase, Moshi moshi) {
    return new InboxManager(dankRedditClient, briteDatabase, moshi);
  }

  @Provides
  @Singleton
  VotingManager provideVotingManager(DankRedditClient dankRedditClient, SharedPreferences sharedPreferences) {
    return new VotingManager(appContext, dankRedditClient, sharedPreferences);
  }

  @Provides
  @Singleton
  SharedPreferences provideSharedPrefs() {
    return PreferenceManager.getDefaultSharedPreferences(appContext);
  }

  @Provides
  @Singleton
  SharedPrefsManager provideSharedPrefsManager(SharedPreferences sharedPrefs) {
    return new SharedPrefsManager(sharedPrefs);
  }

  @Provides
  RxSharedPreferences provideRxSharedPrefs(SharedPreferences sharedPrefs) {
    return RxSharedPreferences.create(sharedPrefs);
  }

  @Provides
  @Singleton
  OkHttpClient provideOkHttpClient() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
        .connectTimeout(NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    if (BuildConfig.DEBUG) {
      HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message -> Timber.tag("OkHttp").d(message));
      logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
      builder.addInterceptor(logging);
      builder.addNetworkInterceptor(new StethoInterceptor());
    }
    builder.addNetworkInterceptor(new OkHttpWholesomeAuthIntercepter());

    return builder.build();
  }

  @Provides
  @Singleton
  Moshi provideMoshi(JacksonHelper jacksonHelper) {
    return new Moshi.Builder()
        .add(AutoValueMoshiAdapterFactory.create())
        .add(new MoshiMessageAdapter(jacksonHelper))
        .add(new MoshiSubmissionAdapter(jacksonHelper))
        .add(new MoshiAccountAdapter(jacksonHelper))
        .build();
  }

  @Provides
  @Singleton
  Retrofit provideRetrofit(OkHttpClient okHttpClient, Moshi moshi) {
    return new Retrofit.Builder()
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .baseUrl("http://saket.me/" /* This isn't used anywhere, but this value is not nullable. */)
        .client(okHttpClient)
        .build();
  }

  @Provides
  @Singleton
  DankApi providesDankApi(Retrofit retrofit) {
    return retrofit.create(DankApi.class);
  }

  @Provides
  @Singleton
  JacksonHelper provideJacksonHelper() {
    return new JacksonHelper(new ObjectMapper());
  }

  /**
   * Used for caching videos.
   */
  @Provides
  @Singleton
  HttpProxyCacheServer provideHttpProxyCacheServer() {
    return new HttpProxyCacheServer(appContext);
  }

  @Provides
  ImgurRepository provideImgurRepository() {
    return new ImgurRepository(appContext);
  }

  @Provides
  StreamableRepository provideStreamableRepository() {
    return new StreamableRepository();
  }

  @Provides
  @Singleton
  BriteDatabase provideBriteDatabase() {
    SqlBrite sqlBrite = new SqlBrite.Builder()
        .logger(message -> Timber.tag("Database").v(message))
        .build();

    BriteDatabase briteDatabase = sqlBrite.wrapDatabaseHelper(new DankSqliteOpenHelper(appContext), Schedulers.io());
    //briteDatabase.setLoggingEnabled(BuildConfig.DEBUG);
    briteDatabase.setLoggingEnabled(false);
    return briteDatabase;
  }

//  @Singleton
//  @Provides
//  MemoryPolicy provideCachingPolicy() {
//    return MemoryPolicy.builder()
//        .setExpireAfter(1)
//        .setExpireAfterTimeUnit(TimeUnit.DAYS)
//        .build();
//  }

  @Provides
  @Singleton
  FileSystem provideCacheFileSystem() {
    try {
      return FileSystemFactory.create(appContext.getCacheDir());
    } catch (IOException e) {
      throw new RuntimeException("Couldn't create FileSystemFactory. Cache dir: " + appContext.getCacheDir());
    }
  }

  @Provides
  @Singleton
  ErrorResolver provideErrorManager() {
    return new ErrorResolver();
  }

  @Provides
  @Singleton
  MessagesNotificationManager provideMessagesNotifManager(SharedPreferences sharedPreferences) {
    return new MessagesNotificationManager(new MessagesNotificationManager.SeenUnreadMessageIdStore(sharedPreferences));
  }

  @Provides
  @Singleton
  UserSession provideUserSessionManager(SharedPreferences sharedPrefs) {
    return new UserSession(sharedPrefs);
  }

  @Provides
  @Singleton
  CommentsManager provideCommentsManager(DankRedditClient dankRedditClient, BriteDatabase database, UserSession userSession,
      @Named("replyDraftStore") SharedPreferences sharedPrefs, Moshi moshi)
  {
    int recycleDraftsOlderThanNumDays = appContext.getResources().getInteger(R.integer.recycle_drafts_older_than_num_days);
    return new CommentsManager(dankRedditClient, database, userSession, sharedPrefs, moshi, recycleDraftsOlderThanNumDays);
  }

  @Provides
  @Singleton
  @Named("replyDraftStore")
  SharedPreferences provideSharedPrefsForReplyDraftStore() {
    return appContext.getSharedPreferences("replyDraftStore", Context.MODE_PRIVATE);
  }

  @Provides
  ConnectivityManager provideConnectivityManager() {
    return (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
  }
}
