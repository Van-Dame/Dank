package me.saket.dank.ui.compose;

import static io.reactivex.android.schedulers.AndroidSchedulers.mainThread;
import static io.reactivex.schedulers.Schedulers.io;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ScrollView;

import com.google.common.base.Strings;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import me.saket.dank.BuildConfig;
import me.saket.dank.R;
import me.saket.dank.data.ContributionFullNameWrapper;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.DankPullCollapsibleActivity;
import me.saket.dank.ui.giphy.GiphyGif;
import me.saket.dank.ui.giphy.GiphyPickerActivity;
import me.saket.dank.ui.submission.CommentsManager;
import me.saket.dank.utils.Keyboards;
import me.saket.dank.utils.Views;
import me.saket.dank.widgets.InboxUI.IndependentExpandablePageLayout;
import timber.log.Timber;

/**
 * For composing comments and message replies. Handles saving and retaining drafts.
 */
public class ComposeReplyActivity extends DankPullCollapsibleActivity implements OnLinkInsertListener {

  private static final String KEY_START_OPTIONS = "startOptions";
  private static final int REQUEST_CODE_PICK_IMAGE = 98;
  private static final int REQUEST_CODE_PICK_GIF = 99;

  @BindView(R.id.composereply_root) IndependentExpandablePageLayout pageLayout;
  @BindView(R.id.toolbar) Toolbar toolbar;
  @BindView(R.id.composereply_progress) View progressView;
  @BindView(R.id.composereply_compose_field_scrollview) ScrollView replyScrollView;
  @BindView(R.id.composereply_compose_field) EditText replyField;
  @BindView(R.id.composereply_format_toolbar) TextFormatToolbarView formatToolbarView;

  @Inject CommentsManager commentsManager;

  private ComposeStartOptions startOptions;

  public static void start(Context context, ComposeStartOptions startOptions) {
    Intent intent = new Intent(context, ComposeReplyActivity.class);
    intent.putExtra(KEY_START_OPTIONS, startOptions);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    Dank.dependencyInjector().inject(this);
    setPullToCollapseEnabled(true);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.compose_reply);
    ButterKnife.bind(this);
    findAndSetupToolbar();

    startOptions = getIntent().getParcelableExtra(KEY_START_OPTIONS);

    // TODO: REMOVEE
    if (BuildConfig.DEBUG && startOptions == null) {
      startOptions = ComposeStartOptions.builder()
          .secondPartyName("Poop")
          .parentContribution(ContributionFullNameWrapper.create("Poop"))
          .preFilledText("Waddup homie")
          .build();
    }

    setTitle(getString(R.string.composereply_title_reply_to, startOptions.secondPartyName()));
    toolbar.setNavigationOnClickListener(o -> onBackPressed());

    setupContentExpandablePage(pageLayout);
    expandFromBelowToolbar();
    pageLayout.setPullToCollapseIntercepter((event, downX, downY, upwardPagePull) -> {
      //noinspection CodeBlock2Expr
      return Views.touchLiesOn(replyScrollView, downX, downY) && replyScrollView.canScrollVertically(upwardPagePull ? 1 : -1);
    });

    String preFilledText = startOptions.preFilledText();
    if (Strings.isNullOrEmpty(preFilledText)) {
      // Restore draft.
      commentsManager.getDraft(ContributionFullNameWrapper.create(startOptions.parentContributionFullName()))
          .subscribeOn(io())
          .observeOn(mainThread())
          .subscribe(draft -> replyField.getText().replace(0, replyField.getText().length(), draft));

    } else {
      Views.setTextWithCursor(replyField, preFilledText);
    }
  }

  @Override
  public void onStop() {
    super.onStop();

    commentsManager.saveDraft(ContributionFullNameWrapper.create(startOptions.parentContributionFullName()), replyField.getText().toString())
        .subscribeOn(io())
        .subscribe();
  }

  @Override
  public void onPostCreate(@Nullable Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);

    formatToolbarView.setActionClickListener((view, markdownAction, markdownBlock) -> {
      Timber.d("--------------------------");
      Timber.i("%s: %s", markdownAction, markdownBlock);

      switch (markdownAction) {
        case INSERT_TEXT_EMOJI:
          String[] unicodeEmojis = getResources().getStringArray(R.array.compose_reply_unicode_emojis);
          PopupMenu emojiMenu = new PopupMenu(this, view, Gravity.TOP);
          for (String unicodeEmoji : unicodeEmojis) {
            emojiMenu.getMenu().add(unicodeEmoji);
          }
          emojiMenu.show();
          emojiMenu.setOnMenuItemClickListener(item -> {
            replyField.getText().replace(replyField.getSelectionStart(), replyField.getSelectionEnd(), item.getTitle());
            return true;
          });
          break;

        case INSERT_LINK:
          // preFilledTitle will be empty when there's no text selected.
          int selectionStart = Math.min(replyField.getSelectionStart(), replyField.getSelectionEnd());
          int selectionEnd = Math.max(replyField.getSelectionStart(), replyField.getSelectionEnd());
          CharSequence preFilledTitle = replyField.getText().subSequence(selectionStart, selectionEnd);
          AddLinkDialog.showPreFilled(getSupportFragmentManager(), preFilledTitle.toString());
          break;

        case INSERT_IMAGE:
          Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
          intent.setType("image/*");
          startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
          break;

        case INSERT_GIF:
          startActivityForResult(GiphyPickerActivity.intent(this), REQUEST_CODE_PICK_GIF);
          break;

        case QUOTE:
        case HEADING:
          insertQuoteOrHeadingMarkdownSyntax(markdownBlock);
          break;

        default:
          if (markdownBlock == null) {
            throw new AssertionError();
          }
          insertMarkdownSyntax(markdownBlock);
          break;
      }
    });
  }

  /**
   * Inserts '>' or '#' at the starting of the line and deletes extra space when nesting.
   */
  private void insertQuoteOrHeadingMarkdownSyntax(MarkdownBlock markdownBlock) {
    char syntax = markdownBlock.prefix().charAt(0);   // '>' or '#'.

    // To keep things simple, we'll always insert the quote at the beginning.
    int currentLineIndex = replyField.getLayout().getLineForOffset(replyField.getSelectionStart());
    int currentLineStartIndex = replyField.getLayout().getLineStart(currentLineIndex);
    boolean isNestingQuotes = replyField.getText().length() > 0 && replyField.getText().charAt(currentLineStartIndex) == syntax;

    int selectionStartCopy = replyField.getSelectionStart();
    int selectionEndCopy = replyField.getSelectionEnd();

    replyField.setSelection(currentLineStartIndex);
    insertMarkdownSyntax(markdownBlock);
    //noinspection ConstantConditions
    int quoteSyntaxLength = markdownBlock.prefix().length();
    replyField.setSelection(selectionStartCopy + quoteSyntaxLength, selectionEndCopy + quoteSyntaxLength);

    // Next, delete extra spaces between nested quotes.
    if (isNestingQuotes) {
      replyField.getText().delete(currentLineStartIndex + 1, currentLineStartIndex + 2);
    }
  }

  private void insertMarkdownSyntax(MarkdownBlock markdownBlock) {
    boolean isSomeTextSelected = replyField.getSelectionStart() != replyField.getSelectionEnd();
    if (isSomeTextSelected) {
      int selectionStart = replyField.getSelectionStart();
      int selectionEnd = replyField.getSelectionEnd();

      Timber.i("selectionStart: %s", selectionStart);
      Timber.i("selectionEnd: %s", selectionEnd);

      replyField.getText().insert(selectionStart, markdownBlock.prefix());
      replyField.getText().insert(selectionEnd + markdownBlock.prefix().length(), markdownBlock.suffix());
      replyField.setSelection(selectionStart + markdownBlock.prefix().length(), selectionEnd + markdownBlock.prefix().length());

    } else {
      //noinspection ConstantConditions
      replyField.getText().insert(replyField.getSelectionStart(), markdownBlock.prefix() + markdownBlock.suffix());
      replyField.setSelection(replyField.getSelectionStart() - markdownBlock.suffix().length());
    }
  }

  private void onGifInsert(GiphyGif giphyGif) {
    int selectionStart = Math.min(replyField.getSelectionStart(), replyField.getSelectionEnd());
    int selectionEnd = Math.max(replyField.getSelectionStart(), replyField.getSelectionEnd());

    String selectedText = replyField.getText().subSequence(selectionStart, selectionEnd).toString();
    onLinkInsert(selectedText, giphyGif.url());

    // Keyboard might have gotten dismissed while the GIF list was being scrolled.
    // Works only if called delayed. Posting to reply field's message queue works.
    replyField.post(() -> Keyboards.show(replyField));
  }

  @Override
  public void onLinkInsert(String title, String url) {
    int selectionStart = Math.min(replyField.getSelectionStart(), replyField.getSelectionEnd());
    int selectionEnd = Math.max(replyField.getSelectionStart(), replyField.getSelectionEnd());

    String linkMarkdown = title.isEmpty()
        ? url
        : String.format("[%s](%s)", title, url);
    replyField.getText().replace(selectionStart, selectionEnd, linkMarkdown);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_CODE_PICK_IMAGE) {
      if (resultCode == Activity.RESULT_OK) {
        lifecycle().onResume()
            .take(1)
            .takeUntil(lifecycle().onPause())
            .subscribe(o -> UploadImageDialog.show(getSupportFragmentManager(), data.getData()));
      }

    } else if (requestCode == REQUEST_CODE_PICK_GIF) {
      if (resultCode == Activity.RESULT_OK) {
        GiphyGif selectedGif = GiphyPickerActivity.handleActivityResult(data);
        onGifInsert(selectedGif);
      }

    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @OnClick(R.id.composereply_send)
  void onClickSend() {
    progressView.setVisibility(View.VISIBLE);
  }

// ======== TOOLBAR MENU ======== //

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    //getMenuInflater().inflate(R.menu.menu_compose_reply, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      default:
        return super.onOptionsItemSelected(item);
    }
  }
}