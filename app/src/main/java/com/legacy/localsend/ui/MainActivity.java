package com.legacy.localsend.ui;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.ClipboardManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.legacy.localsend.R;
import com.legacy.localsend.core.DeviceInfo;
import com.legacy.localsend.core.FileMeta;
import com.legacy.localsend.core.FileSource;
import com.legacy.localsend.core.IncomingFile;
import com.legacy.localsend.core.ReceiveRequest;
import com.legacy.localsend.core.SendFile;
import com.legacy.localsend.ui.kit.BottomNav;
import com.legacy.localsend.ui.kit.CardDialog;
import com.legacy.localsend.ui.kit.IconView;
import com.legacy.localsend.ui.kit.LogoView;
import com.legacy.localsend.ui.kit.SwitchView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单 Activity + 3 tab（接收 / 发送 / 设置），Material 3 风格（自绘组件，API 8 可用）。 */
public class MainActivity extends Activity implements ServerService.UiListener {

    private static final int REQ_PICK = 1001;
    private static final int REQ_WRITE_PERM = 1003;

    private static final int TAB_RECEIVE = 0;
    private static final int TAB_SEND = 1;
    private static final int TAB_SETTINGS = 2;

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ServerService service;
    private boolean serverRunning;
    private int attachAttempts;
    private int currentTab = TAB_RECEIVE;

    private View tabReceive;
    private View tabSend;
    private View tabSettings;
    private BottomNav bottomNav;

    private LogoView logo;
    private TextView receiveAlias;
    private TextView receiveStatus;

    private LinearLayout selectionCard;
    private TextView selectionText;
    private LinearLayout deviceContainer;
    private TextView sendEmpty;
    private final List<ServerService.DeviceEntry> devices = new ArrayList<ServerService.DeviceEntry>();

    private TextView settingsAliasValue;
    private TextView settingsPortValue;
    private TextView settingsThemeValue;
    private TextView settingsDir;
    private TextView settingsFingerprint;
    private TextView settingsVersion;
    private SwitchView autoSwitch;
    private SwitchView darkSwitch;
    private boolean updatingSwitch;

    private CardDialog receiveDialog;
    private int pendingReceiveId;
    private long pendingReceiveTotal;
    private boolean receiveDialogAnswered;

    private CardDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressPercent;
    private TextView progressFile;
    private boolean progressIsSend;
    private CardDialog prepareDialog;

    private CardDialog appPickerDialog;
    private LinearLayout appListContainer;
    private TextView appLoadingView;
    private SwitchView appSystemSwitch;
    private boolean appPickerShowSystem;
    private int appLoadGeneration;

    private long sendTotalBytes;
    private final Map<String, Long> sendProgress = new HashMap<String, Long>();
    private boolean sendCancelledByUser;

    private long recvTotalBytes;
    private final Map<String, Long> recvProgress = new HashMap<String, Long>();

    private final List<SendFile> selectedFiles = new ArrayList<SendFile>();
    private final List<File> tempFiles = new ArrayList<File>();
    private final List<File> pendingTempFiles = new ArrayList<File>();

    private final Runnable attachRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            attachToService();
            // 服务重启期间会先短暂持有旧实例，这里持续重试约 5 秒，确保最终绑定到新实例
            if (attachAttempts++ < 25) {
                uiHandler.postDelayed(this, 200L);
            }
        }
    };

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.load(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Theme.bg));
        setContentView(R.layout.activity_main);
        Theme.applyTo(findViewById(android.R.id.content));
        prefs = Prefs.get(this);
        bindViews();
        bottomNav.refreshTheme();
        bindListeners();
        int initialTab = savedInstanceState == null
                ? TAB_RECEIVE : savedInstanceState.getInt("currentTab", TAB_RECEIVE);
        selectTab(initialTab);
        requestStoragePermissionIfNeeded();
        ServerService.start(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentTab", currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.get(this);
        refreshReceiveHeader();
        refreshSettings();
        attachAttempts = 0;
        uiHandler.removeCallbacks(attachRunnable);
        if (!attachToService()) {
            uiHandler.post(attachRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(attachRunnable);
        closeReceiveDialog(true);
        closeProgressDialog();
        closePrepareDialog();
        closeAppPicker();
        ServerService current = service;
        service = null;
        if (current != null) {
            current.setUiListener(null);
        }
    }

    @Override
    protected void onDestroy() {
        cleanupTempFiles();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private void bindViews() {
        tabReceive = findViewById(R.id.tab_receive_root);
        tabSend = findViewById(R.id.tab_send_root);
        tabSettings = findViewById(R.id.tab_settings_root);
        bottomNav = (BottomNav) findViewById(R.id.bottom_nav);

        logo = (LogoView) findViewById(R.id.receive_logo);
        receiveAlias = (TextView) findViewById(R.id.receive_alias);
        receiveStatus = (TextView) findViewById(R.id.receive_status);

        selectionCard = (LinearLayout) findViewById(R.id.selection_card);
        selectionText = (TextView) findViewById(R.id.selection_text);
        deviceContainer = (LinearLayout) findViewById(R.id.device_container);
        sendEmpty = (TextView) findViewById(R.id.send_empty);

        settingsAliasValue = (TextView) findViewById(R.id.settings_alias_value);
        settingsPortValue = (TextView) findViewById(R.id.settings_port_value);
        settingsThemeValue = (TextView) findViewById(R.id.settings_theme_value);
        settingsDir = (TextView) findViewById(R.id.settings_dir);
        settingsFingerprint = (TextView) findViewById(R.id.settings_fingerprint);
        settingsVersion = (TextView) findViewById(R.id.settings_version);
        autoSwitch = (SwitchView) findViewById(R.id.auto_accept_switch);
        darkSwitch = (SwitchView) findViewById(R.id.dark_mode_switch);

        rebuildIcons();
    }

    private void rebuildIcons() {
        icon(R.id.tile_file_icon).setIcon(IconView.FILE, Theme.primary);
        icon(R.id.tile_media_icon).setIcon(IconView.IMAGE, Theme.primary);
        icon(R.id.tile_clipboard_icon).setIcon(IconView.CLIPBOARD, Theme.primary);
        icon(R.id.tile_text_icon).setIcon(IconView.TEXT, Theme.primary);
        icon(R.id.tile_folder_icon).setIcon(IconView.FOLDER, Theme.primary);
        icon(R.id.tile_apps_icon).setIcon(IconView.APPS, Theme.primary);
        icon(R.id.btn_refresh).setIcon(IconView.REFRESH, Theme.text);
        icon(R.id.btn_manual_add).setIcon(IconView.TARGET, Theme.text);
        icon(R.id.btn_favorite).setIcon(IconView.HEART, Theme.text);
        icon(R.id.btn_gear).setIcon(IconView.GEAR, Theme.text);
        icon(R.id.receive_link_icon).setIcon(IconView.GLOBE, Theme.primary);
        icon(R.id.chevron_alias).setIcon(IconView.CHEVRON, Theme.textSecondary);
        icon(R.id.chevron_port).setIcon(IconView.CHEVRON, Theme.textSecondary);
        icon(R.id.chevron_theme).setIcon(IconView.CHEVRON, Theme.textSecondary);
    }

    private void bindListeners() {
        bottomNav.setListener(new BottomNav.Listener() {
            @Override
            public void onTabSelected(int index) {
                selectTab(index);
            }
        });

        findViewById(R.id.receive_link).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLinkInfoDialog();
            }
        });

        findViewById(R.id.tile_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickFiles("*/*");
            }
        });
        findViewById(R.id.tile_media).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickFiles("image/*");
            }
        });
        findViewById(R.id.tile_clipboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendClipboard();
            }
        });
        findViewById(R.id.tile_text).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showTextInputDialog();
            }
        });
        findViewById(R.id.tile_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toast(getString(R.string.toast_folder_unsupported));
            }
        });
        findViewById(R.id.tile_apps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAppPicker();
            }
        });

        findViewById(R.id.selection_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearSelection();
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerService current = ServerService.getInstance();
                if (current == null) {
                    toast(getString(R.string.toast_service_starting));
                    ServerService.start(MainActivity.this);
                    scheduleAttach();
                    return;
                }
                current.rescan();
            }
        });
        findViewById(R.id.btn_manual_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showManualAddDialog();
            }
        });
        findViewById(R.id.btn_favorite).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toast(getString(R.string.toast_favorite_unsupported));
            }
        });
        findViewById(R.id.btn_gear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectTab(TAB_SETTINGS);
            }
        });

        findViewById(R.id.row_alias).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAliasDialog();
            }
        });
        findViewById(R.id.row_port).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPortDialog();
            }
        });
        findViewById(R.id.row_theme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showThemeDialog();
            }
        });

        autoSwitch.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchView view, boolean checked) {
                if (updatingSwitch) {
                    return;
                }
                prefs.setAutoAccept(checked);
                ServerService.restart(MainActivity.this);
                scheduleAttach();
            }
        });

        darkSwitch.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchView view, boolean checked) {
                if (updatingSwitch) {
                    return;
                }
                prefs.setDarkMode(checked);
                recreateForTheme();
            }
        });
    }

    private IconView icon(int id) {
        return (IconView) findViewById(id);
    }

    private void selectTab(int index) {
        currentTab = index;
        tabReceive.setVisibility(index == TAB_RECEIVE ? View.VISIBLE : View.GONE);
        tabSend.setVisibility(index == TAB_SEND ? View.VISIBLE : View.GONE);
        tabSettings.setVisibility(index == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (bottomNav.getSelectedIndex() != index) {
            bottomNav.select(index, false);
        }
        if (index == TAB_RECEIVE) {
            refreshReceiveHeader();
        } else if (index == TAB_SEND) {
            updateEmptyView();
        } else {
            refreshSettings();
        }
    }

    private boolean attachToService() {
        ServerService current = ServerService.getInstance();
        if (current == null) {
            return false;
        }
        if (current != service) {
            service = current;
            current.setUiListener(this);
        }
        return true;
    }

    private void scheduleAttach() {
        attachAttempts = 0;
        uiHandler.removeCallbacks(attachRunnable);
        uiHandler.post(attachRunnable);
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private void refreshReceiveHeader() {
        if (receiveAlias == null) {
            return;
        }
        receiveAlias.setText(prefs.getAlias());
        int port = prefs.getPort();
        String error = service == null ? null : service.getStatusMessage();
        if (!serverRunning && error != null && error.length() > 0) {
            receiveStatus.setText(getString(R.string.receive_status_error, error));
            receiveStatus.setTextColor(Theme.danger);
        } else if (serverRunning) {
            receiveStatus.setText(getString(R.string.receive_status_on, Integer.valueOf(port)));
            receiveStatus.setTextColor(Theme.textSecondary);
        } else {
            receiveStatus.setText(getString(R.string.receive_status_off, Integer.valueOf(port)));
            receiveStatus.setTextColor(Theme.textSecondary);
        }
    }

    private void refreshSettings() {
        if (settingsAliasValue == null) {
            return;
        }
        settingsAliasValue.setText(prefs.getAlias());
        settingsPortValue.setText(String.valueOf(prefs.getPort()));
        settingsDir.setText(prefs.getReceiveDirectory());
        settingsFingerprint.setText(prefs.getFingerprint());
        settingsVersion.setText(getString(R.string.settings_version_value));
        settingsThemeValue.setText(themeName(prefs.getThemeColor()));
        updatingSwitch = true;
        autoSwitch.setChecked(prefs.isAutoAccept());
        darkSwitch.setChecked(prefs.isDarkMode());
        updatingSwitch = false;
    }

    private void recreateForTheme() {
        if (Build.VERSION.SDK_INT >= 11) {
            recreateCompat();
        } else {
            Intent intent = getIntent();
            finish();
            startActivity(intent);
        }
    }

    @TargetApi(11)
    private void recreateCompat() {
        recreate();
    }

    // ------------------------------------------------------------------
    // ServerService.UiListener
    // ------------------------------------------------------------------

    @Override
    public void onStatusChanged(boolean running, String error) {
        if (!running && ServerService.getInstance() == null) {
            service = null;
        }
        serverRunning = running;
        refreshReceiveHeader();
    }

    @Override
    public void onDevicesChanged(List<ServerService.DeviceEntry> newDevices) {
        devices.clear();
        if (newDevices != null) {
            devices.addAll(newDevices);
        }
        renderDevices();
        updateEmptyView();
    }

    @Override
    public void onReceiveConfirm(ReceiveRequest request, final int requestId) {
        if (isFinishing()) {
            answerReceive(requestId, false);
            return;
        }
        if (pendingReceiveId != 0) {
            answerReceive(requestId, false);
            return;
        }
        pendingReceiveId = requestId;
        pendingReceiveTotal = request == null ? 0L : request.totalSize();
        receiveDialogAnswered = false;

        final String senderIp = request == null || request.senderIp == null ? "?" : request.senderIp;
        final String senderName = request == null ? "未知设备" : request.displayName();
        final int fileCount = request == null || request.files == null ? 0 : request.files.size();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(textView(getString(R.string.dialog_receive_from, senderName, senderIp),
                14f, Theme.textSecondary));
        TextView countView = textView(getString(R.string.dialog_receive_count,
                Integer.valueOf(fileCount), UiUtil.formatSize(pendingReceiveTotal)), 16f, Theme.text);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = dp(8);
        content.addView(countView, countParams);
        if (request != null && request.files != null && !request.files.isEmpty()) {
            TextView listLabel = textView(getString(R.string.dialog_receive_files), 13f, Theme.textSecondary);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(12);
            content.addView(listLabel, labelParams);
            int limit = Math.min(request.files.size(), 60);
            for (int i = 0; i < limit; i++) {
                FileMeta file = request.files.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView nameView = textView(file.displayName(), 14f, Theme.text);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView sizeView = textView(UiUtil.formatSize(file.size), 12f, Theme.textSecondary);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(nameView, nameParams);
                row.addView(sizeView);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = dp(4);
                content.addView(row, rowParams);
            }
        }

        final CardDialog dialog = new CardDialog(this, getString(R.string.dialog_receive_title));
        dialog.setBodyView(content);
        dialog.addButton(getString(R.string.dialog_receive_reject), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                receiveDialogAnswered = true;
                answerReceive(requestId, false);
                pendingReceiveId = 0;
                d.dismiss();
            }
        });
        dialog.addButton(getString(R.string.dialog_receive_accept), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                receiveDialogAnswered = true;
                answerReceive(requestId, true);
                pendingReceiveId = 0;
                recvTotalBytes = pendingReceiveTotal;
                recvProgress.clear();
                d.dismiss();
                showReceiveProgressDialog();
            }
        });
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (!receiveDialogAnswered) {
                    receiveDialogAnswered = true;
                    answerReceive(requestId, false);
                }
                pendingReceiveId = 0;
                receiveDialog = null;
            }
        });
        receiveDialog = dialog;
        try {
            dialog.show();
        } catch (Throwable t) {
            receiveDialog = null;
            receiveDialogAnswered = true;
            answerReceive(requestId, false);
            pendingReceiveId = 0;
        }
    }

    @Override
    public void onReceiveConfirmDismiss() {
        closeReceiveDialog(false);
    }

    @Override
    public void onReceiveProgress(FileMeta file, long bytesReceived, long totalBytes) {
        if (progressDialog == null || progressIsSend) {
            showReceiveProgressDialog();
        }
        updateProgress(false, file, bytesReceived, totalBytes);
    }

    @Override
    public void onReceiveComplete(List<IncomingFile> files) {
        closeProgressDialog();
        recvTotalBytes = 0L;
        recvProgress.clear();
        int count = files == null ? 0 : files.size();
        StringBuilder location = new StringBuilder();
        if (files != null) {
            int limit = Math.min(files.size(), 2);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    location.append('\n');
                }
                location.append(files.get(i).describe());
            }
        }
        toast(getString(R.string.toast_recv_saved, Integer.valueOf(count)));
        if (location.length() > 0) {
            toast(getString(R.string.msg_recv_saved_to, location.toString()));
        }
        prefs.refreshReceiveDirectory();
        refreshSettings();
    }

    @Override
    public void onReceiveFailed(String reason) {
        closeProgressDialog();
        recvTotalBytes = 0L;
        recvProgress.clear();
        showError(getString(R.string.recv_failed_title),
                getString(R.string.msg_recv_failed, String.valueOf(reason)));
    }

    @Override
    public void onSendProgress(DeviceInfo target, FileMeta file, long bytesSent, long totalBytes) {
        if (progressDialog == null || !progressIsSend) {
            return;
        }
        updateProgress(true, file, bytesSent, totalBytes);
    }

    @Override
    public void onSendComplete(DeviceInfo target, List<FileMeta> files) {
        closeProgressDialog();
        cleanupTempFiles();
        clearSelectionWithoutTempCleanup();
        int count = files == null ? 0 : files.size();
        toast(getString(R.string.toast_send_complete, Integer.valueOf(count)));
    }

    @Override
    public void onSendFailed(DeviceInfo target, String reason) {
        closeProgressDialog();
        cleanupTempFiles();
        clearSelectionWithoutTempCleanup();
        if (sendCancelledByUser) {
            sendCancelledByUser = false;
            toast(getString(R.string.toast_send_cancelled));
            return;
        }
        String text = String.valueOf(reason);
        String lower = text.toLowerCase();
        if (lower.indexOf("https") >= 0 || text.indexOf("加密") >= 0) {
            showError(getString(R.string.https_title), text);
        } else {
            showError(getString(R.string.send_failed_title), text);
        }
    }

    @Override
    public void onToast(String message, boolean longDuration) {
        Toast.makeText(this, String.valueOf(message),
                longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onError(String title, String message) {
        showError(String.valueOf(title), String.valueOf(message));
    }

    // ------------------------------------------------------------------
    // 发送：选择文件
    // ------------------------------------------------------------------

    private void pickFiles(String mime) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mime == null ? "*/*" : mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (Build.VERSION.SDK_INT >= 18) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.send_file)), REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_no_file_manager));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<Uri>();
        if (Build.VERSION.SDK_INT >= 16) {
            uris.addAll(ClipDataHelper.uris(data));
        }
        Uri single = data.getData();
        if (uris.isEmpty() && single != null) {
            uris.add(single);
        }
        if (uris.isEmpty()) {
            toast(getString(R.string.toast_no_files));
            return;
        }
        prepareFiles(uris);
    }

    private void prepareFiles(final List<Uri> uris) {
        clearSelection();
        discardPendingTempFiles();
        closePrepareDialog();
        prepareDialog = new CardDialog(this, getString(R.string.toast_prepare_files));
        prepareDialog.setBodyText(getString(R.string.send_preparing));
        prepareDialog.setCancelable(false);
        prepareDialog.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SendFile> files = new ArrayList<SendFile>();
                String error = null;
                try {
                    files.addAll(buildSendFiles(uris));
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
                }
                final String failure = error;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        closePrepareDialog();
                        if (isFinishing()) {
                            return;
                        }
                        if (failure != null) {
                            discardPendingTempFiles();
                            showError(getString(R.string.send_failed_title),
                                    getString(R.string.err_pick_failed, failure));
                            return;
                        }
                        if (files.isEmpty()) {
                            discardPendingTempFiles();
                            toast(getString(R.string.toast_no_files));
                            return;
                        }
                        setSelection(files);
                    }
                });
            }
        }).start();
    }

    private List<SendFile> buildSendFiles(List<Uri> uris) throws IOException {
        List<SendFile> result = new ArrayList<SendFile>();
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            String displayName = null;
            long size = -1L;
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex);
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            } catch (Throwable ignored) {
                // 部分 provider 不支持 OpenableColumns
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (displayName == null || displayName.length() == 0) {
                displayName = uri.getLastPathSegment();
            }
            if (displayName == null || displayName.length() == 0) {
                displayName = "file";
            }
            String safeName = FileMeta.sanitizeFileName(displayName);
            String mime = null;
            try {
                mime = getContentResolver().getType(uri);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (mime == null || mime.length() == 0) {
                mime = SendFile.guessMimeType(safeName);
            }
            FileSource source;
            if (size < 0L) {
                final File cached = copyUriToCache(uri, safeName);
                pendingTempFiles.add(cached);
                size = cached.length();
                source = new FileSource() {
                    @Override
                    public long length() {
                        return cached.length();
                    }

                    @Override
                    public InputStream openStream() throws IOException {
                        return new FileInputStream(cached);
                    }
                };
            } else {
                source = new UriFileSource(getContentResolver(), uri, size);
            }
            FileMeta meta = new FileMeta(UUID.randomUUID().toString(), safeName, size, mime);
            result.add(new SendFile(meta, source));
        }
        return result;
    }

    private File copyUriToCache(Uri uri, String name) throws IOException {
        File dir = new File(getCacheDir(), "outgoing");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建缓存目录");
        }
        File target = new File(dir, System.currentTimeMillis() + "_" + name);
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            output = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            UiUtil.closeQuietly(input);
            if (output != null) {
                try {
                    output.flush();
                } catch (IOException ignored) {
                    // 忽略
                }
                UiUtil.closeQuietly(output);
            }
        }
        return target;
    }

    private void setSelection(List<SendFile> files) {
        clearSelection();
        selectedFiles.addAll(files);
        tempFiles.addAll(pendingTempFiles);
        pendingTempFiles.clear();
        updateSelectionCard();
        selectTab(TAB_SEND);
        toast(getString(R.string.toast_files_selected, Integer.valueOf(files.size())));
    }

    private void updateSelectionCard() {
        if (selectionCard == null) {
            return;
        }
        if (selectedFiles.isEmpty()) {
            selectionCard.setVisibility(View.GONE);
            return;
        }
        long total = 0L;
        for (int i = 0; i < selectedFiles.size(); i++) {
            total += selectedFiles.get(i).meta.size;
        }
        selectionText.setText(getString(R.string.selection_count,
                Integer.valueOf(selectedFiles.size()), UiUtil.formatSize(total)));
        selectionCard.setVisibility(View.VISIBLE);
    }

    private void clearSelection() {
        cleanupTempFiles();
        clearSelectionWithoutTempCleanup();
    }

    private void clearSelectionWithoutTempCleanup() {
        selectedFiles.clear();
        updateSelectionCard();
    }

    private void cleanupTempFiles() {
        for (int i = 0; i < tempFiles.size(); i++) {
            File file = tempFiles.get(i);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        tempFiles.clear();
    }

    private void discardPendingTempFiles() {
        for (int i = 0; i < pendingTempFiles.size(); i++) {
            File file = pendingTempFiles.get(i);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        pendingTempFiles.clear();
    }

    // ------------------------------------------------------------------
    // 发送：文本 / 剪贴板 / 发送到设备
    // ------------------------------------------------------------------

    private void sendClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasText()) {
                toast(getString(R.string.toast_clipboard_empty));
                return;
            }
            CharSequence text = clipboard.getText();
            if (text == null || text.length() == 0) {
                toast(getString(R.string.toast_clipboard_empty));
                return;
            }
            SendFile file = createTextSendFile(getString(R.string.clipboard_file_prefix), text.toString());
            List<SendFile> list = new ArrayList<SendFile>();
            list.add(file);
            setSelection(list);
        } catch (Throwable t) {
            toast(String.valueOf(t.getMessage()));
        }
    }

    private void showTextInputDialog() {
        final EditText input = createInput(false);
        input.setHint(getString(R.string.text_input_hint));
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        final CardDialog dialog = new CardDialog(this, getString(R.string.text_input_title));
        dialog.setBodyView(input);
        dialog.addButton(getString(R.string.btn_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
            }
        });
        dialog.addButton(getString(R.string.btn_send), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String text = input.getText() == null ? "" : input.getText().toString();
                if (text.length() == 0) {
                    toast(getString(R.string.toast_no_files));
                    return;
                }
                try {
                    SendFile file = createTextSendFile(getString(R.string.text_file_prefix), text);
                    List<SendFile> list = new ArrayList<SendFile>();
                    list.add(file);
                    setSelection(list);
                    d.dismiss();
                } catch (Throwable t) {
                    toast(String.valueOf(t.getMessage()));
                }
            }
        });
        dialog.show();
    }

    private SendFile createTextSendFile(String prefix, String content) throws IOException {
        File dir = getCacheDir();
        File file = new File(dir, prefix + System.currentTimeMillis() + ".txt");
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(content.getBytes("UTF-8"));
            output.flush();
        } finally {
            UiUtil.closeQuietly(output);
        }
        pendingTempFiles.add(file);
        final File finalFile = file;        FileMeta meta = new FileMeta(UUID.randomUUID().toString(),
                FileMeta.sanitizeFileName(file.getName()), file.length(), "text/plain");
        return new SendFile(meta, new FileSource() {
            @Override
            public long length() {
                return finalFile.length();
            }

            @Override
            public InputStream openStream() throws IOException {
                return new FileInputStream(finalFile);
            }
        });
    }

    private void sendToDevice(ServerService.DeviceEntry entry) {
        if (entry == null) {
            return;
        }
        if (selectedFiles.isEmpty()) {
            toast(getString(R.string.toast_need_files));
            return;
        }
        if (entry.isHttps()) {
            showError(getString(R.string.https_title), getString(R.string.https_unsupported));
            return;
        }
        ServerService current = ServerService.getInstance();
        if (current == null) {
            toast(getString(R.string.toast_service_starting));
            ServerService.start(this);
            scheduleAttach();
            return;
        }
        startSendProgress();
        current.sendFiles(entry.info, entry.ip, new ArrayList<SendFile>(selectedFiles));
    }

    // ------------------------------------------------------------------
    // 发送：选择应用（读取已安装应用，发送其安装包）
    // ------------------------------------------------------------------

    private void showAppPicker() {
        if (appPickerDialog != null) {
            return;
        }
        appPickerShowSystem = prefs.isShowSystemApps();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = textView(getString(R.string.app_picker_show_system), 14f, Theme.text);
        header.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        appSystemSwitch = new SwitchView(this);
        appSystemSwitch.setChecked(appPickerShowSystem);
        appSystemSwitch.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchView view, boolean checked) {
                appPickerShowSystem = checked;
                prefs.setShowSystemApps(checked);
                loadAppsAsync();
            }
        });
        header.addView(appSystemSwitch, new LinearLayout.LayoutParams(dp(52), dp(32)));
        content.addView(header);

        appLoadingView = textView(getString(R.string.app_picker_loading), 14f, Theme.textSecondary);
        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingParams.topMargin = dp(14);
        loadingParams.bottomMargin = dp(8);
        content.addView(appLoadingView, loadingParams);

        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(appListContainer);

        appPickerDialog = new CardDialog(this, getString(R.string.app_picker_title));
        appPickerDialog.setBodyView(content);
        appPickerDialog.addButton(getString(R.string.app_picker_close),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                    }
                });
        appPickerDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                closeAppPicker();
            }
        });
        try {
            appPickerDialog.show();
        } catch (Throwable t) {
            appPickerDialog = null;
            appListContainer = null;
            appLoadingView = null;
            appSystemSwitch = null;
            toast(String.valueOf(t.getMessage()));
            return;
        }
        loadAppsAsync();
    }

    private void loadAppsAsync() {
        if (appListContainer == null || appLoadingView == null) {
            return;
        }
        appLoadingView.setVisibility(View.VISIBLE);
        appLoadingView.setText(getString(R.string.app_picker_loading));
        appListContainer.removeAllViews();
        final int generation = ++appLoadGeneration;
        final boolean includeSystem = appPickerShowSystem;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppEntry> apps = null;
                String error = null;
                try {
                    apps = queryInstalledApps(includeSystem);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
                }
                final List<AppEntry> result = apps;
                final String failure = error;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != appLoadGeneration || appListContainer == null || isFinishing()) {
                            return;
                        }
                        appLoadingView.setVisibility(View.GONE);
                        appListContainer.removeAllViews();
                        if (failure != null) {
                            appListContainer.addView(textView(
                                    getString(R.string.app_picker_failed, failure),
                                    14f, Theme.danger));
                            return;
                        }
                        if (result == null || result.isEmpty()) {
                            appListContainer.addView(textView(
                                    getString(R.string.app_picker_empty), 14f, Theme.textSecondary));
                            return;
                        }
                        for (int i = 0; i < result.size(); i++) {
                            appListContainer.addView(buildAppRow(result.get(i)));
                        }
                    }
                });
            }
        }).start();
    }

    private List<AppEntry> queryInstalledApps(boolean includeSystem) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        Map<String, AppEntry> unique = new LinkedHashMap<String, AppEntry>();
        if (resolved != null) {
            for (int i = 0; i < resolved.size(); i++) {
                ResolveInfo info = resolved.get(i);
                if (info == null || info.activityInfo == null || info.activityInfo.applicationInfo == null) {
                    continue;
                }
                String packageName = info.activityInfo.packageName;
                if (packageName == null || unique.containsKey(packageName)) {
                    continue;
                }
                ApplicationInfo appInfo = info.activityInfo.applicationInfo;
                boolean system = (appInfo.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (system && !includeSystem) {
                    continue;
                }
                String sourceDir = appInfo.sourceDir;
                if (sourceDir == null || sourceDir.length() == 0) {
                    continue;
                }
                File apk = new File(sourceDir);
                if (!apk.isFile()) {
                    continue;
                }
                String label;
                try {
                    CharSequence text = info.loadLabel(pm);
                    label = text == null ? packageName : text.toString();
                } catch (Throwable t) {
                    label = packageName;
                }
                String version = "";
                try {
                    PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
                    if (packageInfo != null && packageInfo.versionName != null) {
                        version = packageInfo.versionName;
                    }
                } catch (Throwable ignored) {
                    // 部分应用读不到版本号
                }
                Drawable icon = null;
                try {
                    icon = info.loadIcon(pm);
                } catch (Throwable ignored) {
                    // 图标加载失败时留空
                }
                unique.put(packageName, new AppEntry(packageName, label, version,
                        sourceDir, apk.length(), system, icon));
            }
        }
        List<AppEntry> list = new ArrayList<AppEntry>(unique.values());
        final Collator collator;
        Collator instance;
        try {
            instance = Collator.getInstance(java.util.Locale.CHINA);
        } catch (Throwable t) {
            instance = null;
        }
        collator = instance;
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry left, AppEntry right) {
                if (left.system != right.system) {
                    return left.system ? 1 : -1;
                }
                int result;
                if (collator != null) {
                    result = collator.compare(left.label, right.label);
                } else {
                    result = left.label.compareToIgnoreCase(right.label);
                }
                if (result != 0) {
                    return result;
                }
                return left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        return list;
    }

    private View buildAppRow(final AppEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setBackgroundDrawable(Theme.rowPressable());
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (entry.icon != null) {
            icon.setImageDrawable(entry.icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(12);
        row.addView(texts, textParams);

        TextView name = textView(entry.label, 15f, Theme.text);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        String detail;
        if (entry.version != null && entry.version.length() > 0) {
            detail = getString(R.string.app_picker_detail, entry.version,
                    UiUtil.formatSize(entry.size));
        } else {
            detail = getString(R.string.app_picker_detail_size, UiUtil.formatSize(entry.size));
        }
        if (entry.system) {
            detail = getString(R.string.app_picker_system) + " · " + detail;
        }
        TextView detailView = textView(detail, 12f, Theme.textSecondary);
        detailView.setSingleLine(true);
        detailView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(2);
        texts.addView(detailView, detailParams);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (appPickerDialog != null) {
                    appPickerDialog.dismiss();
                }
                closeAppPicker();
                prepareApp(entry);
            }
        });
        return row;
    }

    private void prepareApp(final AppEntry entry) {
        closePrepareDialog();
        prepareDialog = new CardDialog(this, getString(R.string.toast_prepare_files));
        prepareDialog.setBodyText(getString(R.string.app_picker_preparing));
        prepareDialog.setCancelable(false);
        prepareDialog.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                SendFile file = null;
                String error = null;
                try {
                    file = buildAppSendFile(entry);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
                }
                final SendFile result = file;
                final String failure = error;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        closePrepareDialog();
                        if (isFinishing()) {
                            return;
                        }
                        if (failure != null || result == null) {
                            showError(getString(R.string.send_failed_title),
                                    getString(R.string.app_picker_unreadable));
                            return;
                        }
                        List<SendFile> list = new ArrayList<SendFile>();
                        list.add(result);
                        setSelection(list);
                        toast(getString(R.string.app_picker_selected, entry.label));
                    }
                });
            }
        }).start();
    }

    private SendFile buildAppSendFile(AppEntry entry) throws IOException {
        final File apk = new File(entry.sourceDir);
        if (!apk.isFile() || !apk.canRead()) {
            throw new IOException(getString(R.string.app_picker_unreadable));
        }
        String version = entry.version == null ? "" : entry.version.trim();
        String base = entry.label == null || entry.label.length() == 0
                ? entry.packageName : entry.label;
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        if (version.length() > 0) {
            base = base + "_" + version;
        }
        String fileName = FileMeta.sanitizeFileName(base + ".apk");
        FileMeta meta = new FileMeta(UUID.randomUUID().toString(), fileName,
                apk.length(), APK_MIME);
        return new SendFile(meta, new FileSource() {
            @Override
            public long length() {
                return apk.length();
            }

            @Override
            public InputStream openStream() throws IOException {
                return new FileInputStream(apk);
            }
        });
    }

    private void closeAppPicker() {
        appLoadGeneration++;
        CardDialog dialog = appPickerDialog;
        appPickerDialog = null;
        appListContainer = null;
        appLoadingView = null;
        appSystemSwitch = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final String version;
        final String sourceDir;
        final long size;
        final boolean system;
        final Drawable icon;

        AppEntry(String packageName, String label, String version, String sourceDir,
                 long size, boolean system, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.version = version;
            this.sourceDir = sourceDir;
            this.size = size;
            this.system = system;
            this.icon = icon;
        }
    }

    // ------------------------------------------------------------------
    // 手动添加 / 设备详情
    // ------------------------------------------------------------------

    private void showManualAddDialog() {
        final EditText input = createInput(false);
        input.setHint(getString(R.string.manual_add_hint));
        final CardDialog dialog = new CardDialog(this, getString(R.string.manual_add_title));
        dialog.setBodyView(input);
        dialog.addButton(getString(R.string.btn_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
            }
        });
        dialog.addButton(getString(R.string.manual_add_ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String address = input.getText() == null ? "" : input.getText().toString().trim();
                if (address.length() == 0) {
                    toast(getString(R.string.toast_manual_empty));
                    return;
                }
                ServerService current = ServerService.getInstance();
                if (current == null) {
                    toast(getString(R.string.toast_service_starting));
                    ServerService.start(MainActivity.this);
                    scheduleAttach();
                    return;
                }
                current.addManualDevice(address);
                d.dismiss();
            }
        });
        dialog.show();
    }

    private void showLinkInfoDialog() {
        CardDialog dialog = new CardDialog(this, getString(R.string.receive_link));
        dialog.setBodyText(getString(R.string.receive_link_body));
        dialog.addDismissButton(getString(R.string.dialog_ok));
        dialog.show();
    }

    private void showDeviceInfo(ServerService.DeviceEntry entry) {
        if (entry == null) {
            return;
        }
        DeviceInfo info = entry.info;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        addInfoRow(content, getString(R.string.device_info_alias), info.displayName());
        addInfoRow(content, getString(R.string.device_info_model),
                info.deviceModel == null || info.deviceModel.length() == 0
                        ? getString(R.string.device_unknown_model) : info.deviceModel);
        addInfoRow(content, getString(R.string.device_info_type), getString(
                "mobile".equals(info.deviceType) ? R.string.device_type_mobile : R.string.device_type_desktop));
        addInfoRow(content, getString(R.string.device_info_ip), entry.ip);
        addInfoRow(content, getString(R.string.device_info_port), String.valueOf(info.port));
        addInfoRow(content, getString(R.string.device_info_protocol),
                info.isHttps() ? getString(R.string.protocol_https) : getString(R.string.protocol_http));
        addInfoRow(content, getString(R.string.device_info_fingerprint),
                info.fingerprint == null ? "-" : info.fingerprint);
        CardDialog dialog = new CardDialog(this, getString(R.string.device_info_title));
        dialog.setBodyView(content);
        dialog.addDismissButton(getString(R.string.dialog_close));
        dialog.show();
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView labelView = textView(label, 14f, Theme.textSecondary);
        labelView.setMinWidth(dp(70));
        TextView valueView = textView(value, 14f, Theme.text);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labelView);
        row.addView(valueView, valueParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private void showAliasDialog() {
        final EditText input = createInput(false);
        input.setText(prefs.getAlias());
        input.setSelection(input.getText().length());
        final CardDialog dialog = new CardDialog(this, getString(R.string.dialog_alias_title));
        dialog.setBodyView(input);
        dialog.addButton(getString(R.string.btn_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
            }
        });
        dialog.addButton(getString(R.string.dialog_save), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String alias = input.getText() == null ? "" : input.getText().toString().trim();
                if (alias.length() == 0) {
                    toast(getString(R.string.err_alias_empty));
                    return;
                }
                prefs.setAlias(alias);
                refreshReceiveHeader();
                refreshSettings();
                ServerService.restart(MainActivity.this);
                scheduleAttach();
                toast(getString(R.string.toast_setting_saved));
                d.dismiss();
            }
        });
        dialog.show();
    }

    private void showPortDialog() {
        final EditText input = createInput(true);
        input.setText(String.valueOf(prefs.getPort()));
        input.setSelection(input.getText().length());
        final CardDialog dialog = new CardDialog(this, getString(R.string.dialog_port_title));
        dialog.setBodyView(input);
        dialog.addButton(getString(R.string.btn_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
            }
        });
        dialog.addButton(getString(R.string.dialog_save), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                int port;
                try {
                    port = Integer.parseInt(input.getText().toString().trim());
                } catch (Throwable t) {
                    toast(getString(R.string.err_port_invalid));
                    return;
                }
                if (port < Prefs.MIN_PORT || port > Prefs.MAX_PORT) {
                    toast(getString(R.string.err_port_invalid));
                    return;
                }
                prefs.setPort(port);
                refreshReceiveHeader();
                refreshSettings();
                ServerService.restart(MainActivity.this);
                scheduleAttach();
                toast(getString(R.string.toast_setting_saved));
                d.dismiss();
            }
        });
        dialog.show();
    }

    private void showThemeDialog() {
        final CardDialog dialog = new CardDialog(this, getString(R.string.theme_title));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int selected = prefs.getThemeColor();
        for (int i = 0; i < Theme.PALETTE_COUNT; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            row.setBackgroundDrawable(index == selected
                    ? Theme.roundRect(Theme.primaryContainer, 12) : Theme.rowPressable());
            row.setClickable(true);
            row.setFocusable(true);

            View swatch = new View(this);
            swatch.setBackgroundDrawable(Theme.strokedRoundRect(
                    Theme.primaryFor(index), Theme.containerFor(index), 16));
            row.addView(swatch, new LinearLayout.LayoutParams(dp(32), dp(32)));

            TextView name = textView(themeName(index), 15f, Theme.text);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.leftMargin = dp(14);
            row.addView(name, nameParams);

            if (index == selected) {
                IconView check = new IconView(this);
                check.setIcon(IconView.CHECK, Theme.primary);
                row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    prefs.setThemeColor(index);
                    dialog.dismiss();
                    Theme.load(MainActivity.this);
                    recreateForTheme();
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowParams.topMargin = dp(2);
            }
            content.addView(row, rowParams);
        }
        dialog.setBodyView(content);
        dialog.addDismissButton(getString(R.string.dialog_close));
        dialog.show();
    }

    private String themeName(int index) {
        switch (index) {
            case Theme.PALETTE_SAGE:
                return getString(R.string.theme_sage);
            case Theme.PALETTE_ROSE:
                return getString(R.string.theme_rose);
            case Theme.PALETTE_LAVENDER:
                return getString(R.string.theme_lavender);
            case Theme.PALETTE_TERRACOTTA:
                return getString(R.string.theme_terracotta);
            case Theme.PALETTE_TEAL:
                return getString(R.string.theme_teal);
            case Theme.PALETTE_MIST_BLUE:
            default:
                return getString(R.string.theme_mist_blue);
        }
    }

    private void startSendProgress() {
        sendTotalBytes = 0L;
        for (int i = 0; i < selectedFiles.size(); i++) {
            sendTotalBytes += selectedFiles.get(i).meta.size;
        }
        sendProgress.clear();
        sendCancelledByUser = false;
        showProgressDialog(true);
    }

    private void showProgressDialog(final boolean send) {
        closeProgressDialog();
        progressIsSend = send;
        progressDialog = new CardDialog(this,
                getString(send ? R.string.send_progress_title : R.string.recv_progress_title));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        try {
            progressBar.setProgressDrawable(Theme.progressDrawable());
        } catch (Throwable ignored) {
            // 使用系统默认进度条
        }
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        content.addView(progressBar, barParams);
        progressPercent = textView("0%", 12f, Theme.textSecondary);
        LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        percentParams.topMargin = dp(8);
        content.addView(progressPercent, percentParams);
        progressFile = textView(getString(send ? R.string.send_preparing : R.string.recv_waiting),
                14f, Theme.text);
        progressFile.setSingleLine(true);
        progressFile.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fileParams.topMargin = dp(4);
        content.addView(progressFile, fileParams);
        progressDialog.setBodyView(content);
        progressDialog.setCancelable(false);
        if (send) {
            progressDialog.addButton(getString(R.string.btn_cancel), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    sendCancelledByUser = true;
                    d.dismiss();
                    progressDialog = null;
                    ServerService current = ServerService.getInstance();
                    if (current != null) {
                        current.cancelSend();
                    }
                }
            });
        } else {
            progressDialog.addButton(getString(R.string.btn_hide), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    d.dismiss();
                    progressDialog = null;
                }
            });
        }
        progressDialog.show();
    }

    private void showReceiveProgressDialog() {
        if (progressDialog != null && !progressIsSend) {
            return;
        }
        showProgressDialog(false);
    }

    private void updateProgress(boolean send, FileMeta file, long done, long total) {
        if (progressDialog == null || progressIsSend != send) {
            return;
        }
        Map<String, Long> map = send ? sendProgress : recvProgress;
        String key = file == null ? "?" : (file.id != null ? file.id : file.fileName);
        map.put(key, Long.valueOf(Math.max(0L, done)));
        long sum = sumProgress(map);
        long totalBytes = send
                ? (sendTotalBytes > 0L ? sendTotalBytes : total)
                : (recvTotalBytes > 0L ? recvTotalBytes : total);
        int permille = UiUtil.permille(sum, totalBytes);
        if (progressBar != null) {
            progressBar.setProgress(permille);
        }
        if (progressPercent != null) {
            progressPercent.setText((permille / 10) + "%");
        }
        if (progressFile != null && file != null) {
            progressFile.setText(file.displayName());
        }
    }

    private static long sumProgress(Map<String, Long> map) {
        long total = 0L;
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            Long value = entry.getValue();
            if (value != null) {
                total += value.longValue();
            }
        }
        return total;
    }

    private void closeProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {
                // 忽略
            }
            progressDialog = null;
        }
        progressBar = null;
        progressPercent = null;
        progressFile = null;
    }

    private void closePrepareDialog() {
        if (prepareDialog != null) {
            try {
                prepareDialog.dismiss();
            } catch (Throwable ignored) {
                // 忽略
            }
            prepareDialog = null;
        }
    }

    private void closeReceiveDialog(boolean reject) {
        CardDialog dialog = receiveDialog;
        receiveDialog = null;
        if (dialog == null) {
            return;
        }
        if (reject && !receiveDialogAnswered && pendingReceiveId != 0) {
            answerReceive(pendingReceiveId, false);
        }
        receiveDialogAnswered = true;
        pendingReceiveId = 0;
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void answerReceive(int requestId, boolean accepted) {
        ServerService current = ServerService.getInstance();
        if (current != null) {
            current.answerReceive(requestId, accepted);
        }
    }

    private void showError(String title, String message) {
        if (isFinishing()) {
            return;
        }
        CardDialog dialog = new CardDialog(this, title);
        dialog.setBodyText(message);
        dialog.addDismissButton(getString(R.string.dialog_ok));
        dialog.show();
    }

    // ------------------------------------------------------------------
    // 设备列表
    // ------------------------------------------------------------------

    private void updateEmptyView() {
        if (sendEmpty == null) {
            return;
        }
        sendEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void renderDevices() {
        if (deviceContainer == null) {
            return;
        }
        deviceContainer.removeAllViews();
        for (int i = 0; i < devices.size(); i++) {
            final ServerService.DeviceEntry entry = devices.get(i);
            View card = getLayoutInflater().inflate(R.layout.item_device, deviceContainer, false);
            bindDeviceCard(card, entry);
            Theme.applyTo(card);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.topMargin = dp(10);
            }
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendToDevice(entry);
                }
            });
            deviceContainer.addView(card, params);
        }
    }

    private void bindDeviceCard(View view, final ServerService.DeviceEntry entry) {
        IconView deviceIcon = (IconView) view.findViewById(R.id.device_icon);
        TextView nameView = (TextView) view.findViewById(R.id.device_name);
        TextView protocolChip = (TextView) view.findViewById(R.id.chip_protocol);
        TextView modelChip = (TextView) view.findViewById(R.id.chip_model);
        IconView infoIcon = (IconView) view.findViewById(R.id.device_info);

        boolean mobile = "mobile".equals(entry.info.deviceType);
        deviceIcon.setIcon(mobile ? IconView.PHONE : IconView.LAPTOP, Theme.text);
        nameView.setText(entry.displayName());
        protocolChip.setText(entry.isHttps()
                ? getString(R.string.protocol_https) : getString(R.string.protocol_http));
        String model = entry.info.deviceModel;
        if (model != null && model.length() > 0) {
            modelChip.setText(model);
            modelChip.setVisibility(View.VISIBLE);
        } else {
            modelChip.setVisibility(View.GONE);
        }
        infoIcon.setIcon(IconView.INFO, Theme.textSecondary);
        infoIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDeviceInfo(entry);
            }
        });
    }

    // ------------------------------------------------------------------
    // 控件小工具
    // ------------------------------------------------------------------

    private EditText createInput(boolean numeric) {
        EditText input = new EditText(this);
        input.setBackgroundDrawable(Theme.inputDrawable());
        input.setTextColor(Theme.text);
        input.setHintTextColor(Theme.textSecondary);
        input.setTextSize(16f);
        input.setSingleLine(true);
        input.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        input.setLayoutParams(params);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private TextView textView(String text, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    // ------------------------------------------------------------------
    // 权限 / 杂项
    // ------------------------------------------------------------------

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 28) {
            return;
        }
        try {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_WRITE_PERM);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_PERM) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            toast(getString(R.string.toast_permission_denied));
            prefs.refreshReceiveDirectory();
            refreshSettings();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** API 16+ 多选返回值解析；单独成类避免 API 8 加载到 ClipData。 */
    @TargetApi(16)
    private static final class ClipDataHelper {

        private ClipDataHelper() {
        }

        static List<Uri> uris(Intent data) {
            List<Uri> result = new ArrayList<Uri>();
            try {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        ClipData.Item item = clip.getItemAt(i);
                        Uri uri = item == null ? null : item.getUri();
                        if (uri != null) {
                            result.add(uri);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 退回到单文件
            }
            return result;
        }
    }
}


