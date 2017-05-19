package me.saket.dank.data;

import static hu.akarnokd.rxjava.interop.RxJavaInterop.toV2Observable;
import static hu.akarnokd.rxjava.interop.RxJavaInterop.toV2Single;

import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.CheckResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.auto.value.AutoValue;
import com.squareup.sqlbrite.BriteDatabase;

import net.dean.jraw.models.Listing;
import net.dean.jraw.models.Message;
import net.dean.jraw.models.PrivateMessage;
import net.dean.jraw.paginators.InboxPaginator;
import net.dean.jraw.paginators.Paginator;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Consumer;
import me.saket.dank.ui.user.messages.InboxFolder;
import me.saket.dank.ui.user.messages.StoredMessage;
import me.saket.dank.utils.JacksonHelper;
import me.saket.dank.utils.JrawUtils;

public class InboxManager {

  /**
   * The maximum count of items that will be fetched on every pagination iteration.
   */
  public static final int MESSAGES_FETCHED_PER_PAGE = Paginator.DEFAULT_LIMIT * 2;

  private final DankRedditClient dankRedditClient;
  private final BriteDatabase briteDatabase;
  private final JacksonHelper jacksonHelper;

  public InboxManager(DankRedditClient dankRedditClient, BriteDatabase briteDatabase, JacksonHelper jacksonHelper) {
    this.dankRedditClient = dankRedditClient;
    this.briteDatabase = briteDatabase;
    this.jacksonHelper = jacksonHelper;
  }

  /**
   * Stream of all messages in <var>folder</var>
   */
  @CheckResult
  public Observable<List<Message>> messages(InboxFolder folder) {
    return toV2Observable(briteDatabase
        .createQuery(StoredMessage.TABLE_NAME, StoredMessage.QUERY_GET_ALL_IN_FOLDER, folder.name())
        .mapToList(StoredMessage.mapMessageFromCursor(jacksonHelper)));
  }

  /**
   * Fetch messages after the oldest message we locally have in <var>folder</var>.
   */
  @CheckResult
  public Single<FetchMoreResult> fetchMoreMessages(InboxFolder folder) {
    return getPaginationAnchor(folder)
        .flatMap(anchor -> fetchMessagesFromAnchor(folder, anchor))
        .doOnSuccess(saveMessages(folder, false))
        .map(fetchedMessages -> FetchMoreResult.create(fetchedMessages.isEmpty()));
  }

  /**
   * Fetch most recent messages and remove any existing messages. Unlike {@link #fetchMoreMessages(InboxFolder)},
   * this does not use the oldest message as the anchor.
   */
  @CheckResult
  public Single<FetchMoreResult> refreshMessages(InboxFolder folder) {
    return fetchMessagesFromAnchor(folder, PaginationAnchor.createEmpty())
        .doOnSuccess(saveMessages(folder, true))
        .map(fetchedMessages -> FetchMoreResult.create(fetchedMessages.isEmpty()));
  }

  @CheckResult
  private Single<List<Message>> fetchMessagesFromAnchor(InboxFolder folder, PaginationAnchor paginationAnchor) {
    return dankRedditClient.withAuth(Single.fromCallable(() -> {
      InboxPaginator paginator = dankRedditClient.userMessagePaginator(folder);
      paginator.setLimit(MESSAGES_FETCHED_PER_PAGE);

      if (!paginationAnchor.isEmpty()) {
        paginator.setStartAfterThing(paginationAnchor.fullName());
      }

      // Try fetching a minimum of 10 items. Useful for comment and post replies
      // where we have to filter the messages locally.
      List<Message> minimum10Messages = new ArrayList<>();

      while (paginator.hasNext() && minimum10Messages.size() < 10) {
        // paginator.next() makes an API call.
        Listing<Message> nextSetOfMessages = paginator.next();

        for (Message nextMessage : nextSetOfMessages) {
          switch (folder) {
            case UNREAD:
            case PRIVATE_MESSAGES:
            case USERNAME_MENTIONS:
              minimum10Messages.add(nextMessage);
              break;

            case COMMENT_REPLIES:
              if ("comment reply".equals(nextMessage.getSubject())) {
                minimum10Messages.add(nextMessage);
              }
              break;

            case POST_REPLIES:
              if ("post reply".equals(nextMessage.getSubject())) {
                minimum10Messages.add(nextMessage);
              }
              break;

            default:
              throw new UnsupportedOperationException();
          }
        }
      }

      return minimum10Messages;
    }));
  }

  /**
   * Create a PaginationAnchor from the last message in <var>folder</var>.
   */
  @CheckResult
  private Single<PaginationAnchor> getPaginationAnchor(InboxFolder folder) {
    StoredMessage dummyDefaultValue = StoredMessage.create("-1", new PrivateMessage(null), 0, InboxFolder.PRIVATE_MESSAGES);

    return toV2Single(briteDatabase.createQuery(StoredMessage.TABLE_NAME, StoredMessage.QUERY_GET_LAST_IN_FOLDER, folder.name())
        .mapToOneOrDefault(StoredMessage.mapFromCursor(jacksonHelper), dummyDefaultValue)
        .first()
        .map(lastStoredMessage -> {
          if (lastStoredMessage == dummyDefaultValue) {
            return PaginationAnchor.createEmpty();

          } else {
            Message lastMessage = lastStoredMessage.message();

            // Private messages can have nested replies. Go through them and find the last one.
            if (lastMessage instanceof PrivateMessage) {
              JsonNode repliesNode = lastMessage.getDataNode().get("replies");

              if (repliesNode.isObject()) {
                // Replies are present.
                //noinspection MismatchedQueryAndUpdateOfCollection
                Listing<Message> lastMessageReplies = new Listing<>(repliesNode.get("data"), Message.class);
                Message lastMessageLastReply = lastMessageReplies.get(lastMessageReplies.size() - 1);
                return PaginationAnchor.create(lastMessageLastReply.getFullName());
              }
            }

            return PaginationAnchor.create(lastMessage.getFullName());
          }
        })
        .toSingle());
  }

  private Consumer<List<Message>> saveMessages(InboxFolder folder, boolean removeExistingMessages) {
    return fetchedMessages -> {
      try (BriteDatabase.Transaction transaction = briteDatabase.newTransaction()) {
        if (removeExistingMessages) {
          briteDatabase.delete(StoredMessage.TABLE_NAME, StoredMessage.QUERY_WHERE_FOLDER, folder.name());
        }

        for (Message fetchedMessage : fetchedMessages) {
          StoredMessage messageToStore = StoredMessage.create(fetchedMessage.getId(), fetchedMessage, JrawUtils.createdTimeUtc(fetchedMessage), folder);
          briteDatabase.insert(StoredMessage.TABLE_NAME, messageToStore.toContentValues(jacksonHelper), SQLiteDatabase.CONFLICT_REPLACE);
        }
        transaction.markSuccessful();
      }
    };
  }

  @AutoValue
  public abstract static class FetchMoreResult {
    public abstract boolean wasEmpty();

    public static FetchMoreResult create(boolean empty) {
      return new AutoValue_InboxManager_FetchMoreResult(empty);
    }
  }

// ======== READ STATUS ======== //

  @CheckResult
  public Completable setRead(Message message, boolean read) {
    return Completable.fromAction(() -> dankRedditClient.redditInboxManager().setRead(read, message));
  }

  @CheckResult
  public Completable setAllRead() {
    return Completable.fromAction(() -> dankRedditClient.redditInboxManager().setAllRead());
  }

}