/*
Copyright (c) 2017-2019 Divested Computing Group
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package io.github.acidefluorhydrique.geminiassist;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.Toast;
import android.webkit.ValueCallback;
import android.net.Uri;

import androidx.webkit.URLUtilCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.woheller69.freeDroidWarn.FreeDroidWarn;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView chatWebView = null;
    private ImageButton restrictedButton = null;
    private WebSettings chatWebSettings = null;
    private CookieManager chatCookieManager = null;
    private final Context context = this;
    private SwipeTouchListener swipeTouchListener;
    private String TAG ="geminiAssist";
    private String urlToLoad = "https://gemini.google.com/";
    private static boolean restricted = true;

    private static final ArrayList<String> allowedDomains = new ArrayList<String>();

    private ValueCallback<Uri[]> mUploadMessage;
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final static int STORAGE_PERMISSION_REQUEST_CODE = 101;

    private static final String GEMINI_ORIGIN = "https://gemini.google.com";
    private static final String DOWNLOAD_JS_OBJECT = "geminiAssistDownload";

    private android.webkit.PermissionRequest pendingPermissionRequest;  //waits for the RECORD_AUDIO runtime permission
    private String[] pendingBlobDownload;  //url, filename, mimetype waiting for the storage permission

    @Override
    protected void onPause() {
        if (chatCookieManager!=null) chatCookieManager.flush();
        swipeTouchListener = null;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (restricted) restrictedButton.setImageDrawable(getDrawable(R.drawable.restricted));
        else restrictedButton.setImageDrawable(getDrawable(R.drawable.unrestricted));

        restrictedButton.setOnClickListener(v -> {
            restricted = !restricted;
            if (restricted) {
                restrictedButton.setImageDrawable(getDrawable(R.drawable.restricted));
                Toast.makeText(context,R.string.urls_restricted,Toast.LENGTH_SHORT).show();
                chatWebSettings.setUserAgentString(modUserAgent());
            }
            else {
                restrictedButton.setImageDrawable(getDrawable(R.drawable.unrestricted));
                Toast.makeText(context,R.string.all_urls,Toast.LENGTH_SHORT).show();
                chatWebSettings.setUserAgentString(modUserAgent()); //Needed for login via Google
            }
            chatWebView.reload();
        });

        restrictedButton.setOnLongClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.reset_chat)
                    .setMessage(R.string.reset_chat_confirm)
                    .setPositiveButton(R.string.dialog_OK_button, (dialog, which) -> {
                        resetChat();
                        Toast.makeText(context, R.string.reset_chat_done, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.dialog_NO_button, null)
                    .show();
            return true;
        });

        swipeTouchListener = new SwipeTouchListener(context) {
            public void onSwipeBottom() {
                if (!chatWebView.canScrollVertically(0)) {
                    restrictedButton.setVisibility(View.VISIBLE);
                }
            }
            public void onSwipeTop(){
                    restrictedButton.setVisibility(View.GONE);
            }
        };

        chatWebView.setOnTouchListener(swipeTouchListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        restricted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Create the WebView
        chatWebView = findViewById(R.id.chatWebView);
        registerForContextMenu(chatWebView);
        restrictedButton = findViewById(R.id.restricted);
        makeButtonDraggable();

        //Set cookie options
        chatCookieManager = CookieManager.getInstance();
        chatCookieManager.setAcceptCookie(true);
        chatCookieManager.setAcceptThirdPartyCookies(chatWebView, false);

        //Restrict what gets loaded
        initURLs();
        registerForContextMenu(chatWebView);

        chatWebView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.")) {  //this error occurs when user copies to clipboard
                    Toast.makeText(context, R.string.error_copy,Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                //ACTION_GET_CONTENT grants access to the picked file, no storage permission needed
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }

                mUploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (request.getResources().length > 0 && request.getResources()[0].equals(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            // Keep the request and answer it in onRequestPermissionsResult, otherwise the first recording never starts
                            if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
                            pendingPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123);
                        }
                    } else {
                        request.deny();
                    }
                } else {
                    request.grant(request.getResources()); // For older Android versions, permissions are granted at install time
                }
            }

        });  //needed to share link

        chatWebView.setWebViewClient(new WebViewClient() {
            //Keep these in sync!
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (!restricted) return null;

                if (request.getUrl().toString().equals("about:blank")) {
                    return null;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs that aren't HTTPS
                }
                if (!isAllowed(request.getUrl().getHost())) {
                    Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs not on ALLOWLIST
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!restricted) return false;

                if (request.getUrl().toString().equals("about:blank")) {
                    return false;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    if (isLinkTap(request)) return openInBrowser(request.getUrl());
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return true; //Deny URLs that aren't HTTPS
                }
                if (!isAllowed(request.getUrl().getHost())) {
                    if (isLinkTap(request)) return openInBrowser(request.getUrl());
                    Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return true; //Deny URLs not on ALLOWLIST
                }
                return false;
            }

            //WebResourceError is API 23+, and the framework only calls this overload on API 23+
            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "[onReceivedError] " + error.getErrorCode() + ": " + error.getDescription() + " @ " + request.getUrl());
                }
            }
        });

        chatWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("blob:")) {  //DownloadManager only handles http(s), e.g. generated images are blobs
                downloadBlob(url, URLUtilCompat.getFilenameFromContentDisposition(contentDisposition), mimetype);
                return;
            }
            Uri source = Uri.parse(url);
            Log.d(TAG,"DownloadManager: " + url);
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
            request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
            request.addRequestHeader("Referer", url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
            String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
            if (filename == null) filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);  // only if getFilenameFromContentDisposition does not work and returns null
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            assert dm != null;
            dm.enqueue(request);
        });

        //Bridge used by downloadBlob(). Only the Gemini origin can post to it, even in unrestricted mode.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(chatWebView, DOWNLOAD_JS_OBJECT, Collections.singleton(GEMINI_ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> onBlobReceived(message.getData()));
        }
        //Pages often revoke a blob URL right after starting the download, before downloadBlob() can read it
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(chatWebView,
                    "(function(){var revoke=URL.revokeObjectURL;" +
                    "URL.revokeObjectURL=function(u){setTimeout(function(){revoke.call(URL,u);},60000);};})();",
                    Collections.singleton(GEMINI_ORIGIN));
        }

        //Set more options
        chatWebSettings = chatWebView.getSettings();
        //Enable some WebView features
        chatWebSettings.setJavaScriptEnabled(true);
        chatWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        chatWebSettings.setDomStorageEnabled(true);
        //Disable some WebView features
        chatWebSettings.setAllowContentAccess(false);
        chatWebSettings.setAllowFileAccess(false);
        chatWebSettings.setBuiltInZoomControls(false);
        chatWebSettings.setDatabaseEnabled(false);
        chatWebSettings.setDisplayZoomControls(false);
        chatWebSettings.setSaveFormData(false);
        chatWebSettings.setGeolocationEnabled(false);
        chatWebSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        //Load ChatGPT
        chatWebView.loadUrl(urlToLoad);
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this,"https://github.com/acidefluorhydrique/geminiAssist");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (chatWebView.canGoBack() && !chatWebView.getUrl().equals("about:blank")) {
                        chatWebView.goBack();
                    } else {
                        finish();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void resetChat()  {

        chatWebView.clearCache(true);
        chatWebView.clearFormData();
        chatWebView.clearHistory();
        chatWebView.clearMatches();
        chatWebView.clearSslPreferences();
        chatCookieManager.removeSessionCookie();
        chatCookieManager.removeAllCookie();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        chatWebView.loadUrl(urlToLoad);


    }

    //Let the user move the button away from Gemini's own controls, e.g. the temporary chat button (issue #2).
    //The position is stored as a fraction of the free space, so it survives rotation and resizing.
    @SuppressLint("ClickableViewAccessibility")  //taps are not consumed here, the button still handles click and long click
    private void makeButtonDraggable() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        View parent = (View) restrictedButton.getParent();
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        //Restore when the screen size changes, and when the button reappears after being hidden (it has no size while GONE)
        View.OnLayoutChangeListener restorePosition = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                moveButton(prefs.getFloat("buttonX", 1f) * (parent.getWidth() - restrictedButton.getWidth()),
                        prefs.getFloat("buttonY", 0f) * (parent.getHeight() - restrictedButton.getHeight()));
            }
        };
        parent.addOnLayoutChangeListener(restorePosition);
        restrictedButton.addOnLayoutChangeListener(restorePosition);

        restrictedButton.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = v.getX();
                        downY = v.getY();
                        dragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                            //Cancel the pending click and long click, the drag owns the gesture from now on
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            v.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (dragging) moveButton(downX + dx, downY + dy);
                        return dragging;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) return false;
                        dragging = false;
                        int freeX = parent.getWidth() - v.getWidth();
                        int freeY = parent.getHeight() - v.getHeight();
                        prefs.edit()
                                .putFloat("buttonX", freeX > 0 ? v.getX() / freeX : 1f)
                                .putFloat("buttonY", freeY > 0 ? v.getY() / freeY : 0f)
                                .apply();
                        return true;
                }
                return false;
            }
        });
    }

    private void moveButton(float x, float y) {
        View parent = (View) restrictedButton.getParent();
        restrictedButton.setX(Math.max(0, Math.min(x, parent.getWidth() - restrictedButton.getWidth())));
        restrictedButton.setY(Math.max(0, Math.min(y, parent.getHeight() - restrictedButton.getHeight())));
    }

    //A navigation the user started by tapping a web link (not a redirect, script or subframe)
    private static boolean isLinkTap(WebResourceRequest request) {
        String scheme = request.getUrl().getScheme();
        return request.isForMainFrame() && request.hasGesture()
                && ("https".equals(scheme) || "http".equals(scheme));
    }

    private boolean openInBrowser(Uri url) {
        Log.d(TAG, "[openInBrowser] " + url);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    //A blob URL only exists inside the page that created it, so the page reads it and posts the bytes back
    private void downloadBlob(String url, String filename, String mimetype) {
        if (!url.startsWith("blob:" + GEMINI_ORIGIN + "/") || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "[downloadBlob] Unsupported blob download: " + url);
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBlobDownload = new String[]{url, filename, mimetype};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            return;
        }
        Log.d(TAG, "[downloadBlob] " + url);
        String js = "(function(){" +
                "var post=function(m){" + DOWNLOAD_JS_OBJECT + ".postMessage(JSON.stringify(m));};" +
                "fetch(" + JSONObject.quote(url) + ")" +
                ".then(function(r){return r.blob();})" +
                ".then(function(b){" +
                "var fr=new FileReader();" +
                "fr.onload=function(){post({name:" + JSONObject.quote(filename == null ? "" : filename) +
                ",type:b.type||" + JSONObject.quote(mimetype == null ? "" : mimetype) + ",data:fr.result});};" +
                "fr.onerror=function(){post({error:String(fr.error)});};" +
                "fr.readAsDataURL(b);})" +
                ".catch(function(e){post({error:String(e)});});" +
                "})();";
        chatWebView.evaluateJavascript(js, null);
    }

    private void onBlobReceived(String json) {
        new Thread(() -> {  //decoding and writing a large image would block the UI thread
            try {
                JSONObject blob = new JSONObject(json);
                if (blob.has("error")) throw new IOException(blob.getString("error"));
                String mimetype = blob.optString("type");
                String data = blob.getString("data");
                byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
                String filename = blobFilename(blob.optString("name"), mimetype);
                String saved = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? saveToMediaStore(bytes, filename, mimetype)
                        : saveToDownloadsDir(bytes, filename, mimetype);
                runOnUiThread(() -> Toast.makeText(context, getString(R.string.download_saved) + "\n" + saved, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "[onBlobReceived] Saving blob failed", e);
                runOnUiThread(() -> Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static String blobFilename(String name, String mimetype) {
        name = name.replaceAll("[/\\\\:]", "_");  //never let the page pick a directory
        if (name.isEmpty() || name.startsWith(".")) {
            name = "Gemini_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype);
        if (extension != null && !name.contains(".")) name += "." + extension;
        return name;
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private String saveToMediaStore(byte[] bytes, String filename, String mimetype) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mimetype.isEmpty() ? "application/octet-stream" : mimetype);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (item == null) throw new IOException("MediaStore insert failed for " + filename);
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot open " + item);
            out.write(bytes);
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return filename;
    }

    private String saveToDownloadsDir(byte[] bytes, String filename, String mimetype) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        File file = new File(dir, filename);
        for (int i = 1; file.exists(); i++) file = new File(dir, base + " (" + i + ")" + extension);  //do not overwrite
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                mimetype.isEmpty() ? null : new String[]{mimetype}, null);
        return file.getName();
    }

    private static void initURLs() {
        allowedDomains.clear();  //onCreate can run more than once, do not accumulate duplicates
        //Allowed domains. An entry covers exactly that host plus its subdomains,
        //see isAllowed(). Keep this list as small as the app still works with.
        allowedDomains.add("gemini.google.com");      //the chat app itself
        allowedDomains.add("accounts.google.com");    //sign-in
        allowedDomains.add("apis.google.com");        //sign-in and account switcher scripts
        allowedDomains.add("play.google.com");        //part of the sign-in redirect chain
        allowedDomains.add("www.google.com");         //reCAPTCHA during sign-in
        allowedDomains.add("gstatic.com");            //static assets and fonts
        allowedDomains.add("googleusercontent.com");  //avatars, uploaded and generated images
        allowedDomains.add("googleapis.com");         //backend APIs used by the app
    }

    private static boolean isAllowed(String host) {
        if (host == null) return false;
        for (String domain : allowedDomains) {
            //Match on a dot boundary so that e.g. "notgoogle.com" does not pass as "google.com"
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    String dataString = intent.getDataString();
                    if (dataString != null) {
                        result = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        WebView.HitTestResult result = chatWebView.getHitTestResult();
        String url = "";
        if (result.getExtra() != null) {
            if (result.getType() == IMAGE_TYPE) {
                url = result.getExtra();
                if (url.startsWith("blob:")) {
                    downloadBlob(url, null, null);
                    return;
                }
                Uri source = Uri.parse(url);
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
                request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
                request.addRequestHeader("Referer", url);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
                String filename = URLUtil.guessFileName(url, null, "image/jpeg");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                assert dm != null;
                dm.enqueue(request);
            } else if (result.getType() == SRC_IMAGE_ANCHOR_TYPE) {
                //getExtra() is the image here, the link target is only delivered asynchronously
                Message msg = new Handler(Looper.getMainLooper(), m -> {
                    copyIfNotAllowed(m.getData().getString("url"));
                    return true;
                }).obtainMessage();
                chatWebView.requestFocusNodeHref(msg);
            } else if (result.getType() == SRC_ANCHOR_TYPE) {
                copyIfNotAllowed(result.getExtra());
            }
        }
    }

    //Copy URLs that are not allowed to open to clipboard
    private void copyIfNotAllowed(String url) {
        if (url == null) return;
        String host = Uri.parse(url).getHost();
        if (host != null && !isAllowed(host)) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
        }
    }

    public String modUserAgent(){

        String newPrefix = "Mozilla/5.0 (X11; Linux "+ System.getProperty("os.arch") +")";

        String newUserAgent=WebSettings.getDefaultUserAgent(context);
        String prefix = newUserAgent.substring(0, newUserAgent.indexOf(")") + 1);
         try {
                newUserAgent=newUserAgent.replace(prefix,newPrefix);
            } catch (Exception e) {
                e.printStackTrace();
            }
         return newUserAgent;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == 123) {
            if (pendingPermissionRequest != null) {  //answer the WebView request that was waiting for this
                if (granted) pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                else pendingPermissionRequest.deny();
                pendingPermissionRequest = null;
            }
            if (!granted) {
                Toast.makeText(context, R.string.mic_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            String[] download = pendingBlobDownload;
            pendingBlobDownload = null;
            if (granted && download != null) downloadBlob(download[0], download[1], download[2]);
            else Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

}
