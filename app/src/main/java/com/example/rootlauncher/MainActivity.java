package com.example.rootlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // ============================================================
    // UI
    // ============================================================

    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;

    // ============================================================
    // Script
    // ============================================================

    private final ArrayList<String> scriptList =
            new ArrayList<>();

    private ScriptAdapter adapter;

    // ============================================================
    // 当前 ELF
    // ============================================================

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean elfRunning = false;

    private volatile String pendingScriptPath = null;

    // ============================================================
    // Preferences
    // ============================================================

    private android.content.SharedPreferences prefs;

    // ============================================================
    // BusyBox
    // ============================================================

    private File busyboxFile;

    // ============================================================
    // Keyboard
    // ============================================================

    private boolean keyboardVisible = false;

    private static final int SCRIPT_LIST_KEYBOARD_DP = 120;

    // ============================================================
    // 内置文件
    // ============================================================

    private static final String BUILTIN_KAIROS =
            "Kairos_Driver_Loader_Release_90f76e9.sh";

    private static final String BUILTIN_TIME =
            "TIME_Cloud_Loader_Release_1732727.sh";

    private static final String BUSYBOX_ASSET =
            "busybox";

    // ============================================================
    // 统一运行目录
    // ============================================================

    private static final String RUNTIME_DIR =
            "/data/local/tmp/com.example.rootlauncher/files";

    private static final String RUNTIME_BUSYBOX =
            RUNTIME_DIR + "/busybox";

    // ============================================================
    // 文件选择器
    // ============================================================

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode()
                                != Activity.RESULT_OK) {
                            return;
                        }

                        if (result.getData() == null) {
                            return;
                        }

                        Uri uri =
                                result.getData().getData();

                        if (uri == null) {
                            return;
                        }

                        String displayName =
                                getFileName(uri);

                        if (displayName == null
                                || displayName.length() == 0) {

                            displayName =
                                    "script_"
                                            + System.currentTimeMillis()
                                            + ".sh";
                        }

                        final String finalDisplayName =
                                sanitizeFileName(displayName);

                        new Thread(() -> {

                            File tempFile = null;

                            try {

                                // ------------------------------------------------
                                // 复制到 App 私有目录
                                // ------------------------------------------------

                                tempFile =
                                        new File(
                                                getFilesDir(),
                                                "import_"
                                                        + System.currentTimeMillis()
                                                        + "_"
                                                        + finalDisplayName
                                        );

                                InputStream is =
                                        getContentResolver()
                                                .openInputStream(uri);

                                if (is == null) {

                                    appendText(
                                            "[添加失败] 无法读取文件\n"
                                    );

                                    return;
                                }

                                FileOutputStream fos =
                                        new FileOutputStream(
                                                tempFile
                                        );

                                byte[] buffer =
                                        new byte[8192];

                                int len;

                                while ((len =
                                        is.read(buffer)) > 0) {

                                    fos.write(
                                            buffer,
                                            0,
                                            len
                                    );
                                }

                                is.close();
                                fos.close();

                                // ------------------------------------------------
                                // 检查
                                // ------------------------------------------------

                                if (!tempFile.exists()
                                        || tempFile.length() == 0) {

                                    appendText(
                                            "[添加失败] 文件为空\n"
                                    );

                                    return;
                                }

                                appendText(
                                        "[+] 文件已读取："
                                                + finalDisplayName
                                                + "\n"
                                                + "[+] 大小："
                                                + tempFile.length()
                                                + " bytes\n"
                                );

                                // ------------------------------------------------
                                // Root
                                // ------------------------------------------------

                                if (!checkRoot()) {

                                    appendText(
                                            "[添加失败] 当前没有 Root 权限\n"
                                    );

                                    return;
                                }

                                // ------------------------------------------------
                                // 创建运行目录
                                // ------------------------------------------------

                                if (!prepareRuntimeDir()) {

                                    appendText(
                                            "[添加失败] 无法创建运行目录\n"
                                    );

                                    return;
                                }

                                // ------------------------------------------------
                                // 目标
                                // ------------------------------------------------

                                String runtimePath =
                                        RUNTIME_DIR
                                                + "/"
                                                + finalDisplayName;

                                // ------------------------------------------------
                                // Root 复制
                                // ------------------------------------------------

                                if (!copyFileAsRoot(
                                        tempFile.getAbsolutePath(),
                                        runtimePath
                                )) {

                                    appendText(
                                            "[添加失败] 无法复制到运行目录\n"
                                    );

                                    return;
                                }

                                // ------------------------------------------------
                                // chmod
                                // ------------------------------------------------

                                if (!chmod755(runtimePath)) {

                                    appendText(
                                            "[添加失败] chmod 755 失败\n"
                                    );

                                    return;
                                }

                                // ------------------------------------------------
                                // 检查 ELF Magic
                                // ------------------------------------------------

                                String magic =
                                        readFileMagicAsRoot(
                                                runtimePath
                                        );

                                if (magic != null) {

                                    appendText(
                                            "[+] 文件头："
                                                    + magic
                                                    + "\n"
                                    );

                                    if (magic.startsWith(
                                            "7f 45 4c 46"
                                    )) {

                                        appendText(
                                                "[+] 检测到 ELF 文件\n"
                                        );

                                    } else {

                                        appendText(
                                                "[!] 注意：文件头不是标准 ELF\n"
                                        );
                                    }
                                }

                                // ------------------------------------------------
                                // 加入列表
                                // ------------------------------------------------

                                synchronized (scriptList) {

                                    if (!scriptList.contains(
                                            runtimePath
                                    )) {

                                        scriptList.add(
                                                runtimePath
                                        );
                                    }
                                }

                                saveScripts();

                                final String addedName =
                                        finalDisplayName;

                                runOnUiThread(() -> {

                                    if (adapter != null) {

                                        adapter.notifyDataSetChanged();
                                    }

                                    appendText(
                                            "[+] 已添加："
                                                    + addedName
                                                    + "\n"
                                    );
                                });

                            } catch (Exception e) {

                                appendText(
                                        "[添加文件失败] "
                                                + safeMessage(e)
                                                + "\n"
                                );

                            } finally {

                                if (tempFile != null) {

                                    try {

                                        if (tempFile.exists()) {

                                            tempFile.delete();
                                        }

                                    } catch (Exception ignored) {
                                    }
                                }
                            }

                        }).start();
                    }
            );

    // ============================================================
    // onCreate
    // ============================================================

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        setContentView(
                R.layout.activity_main
        );

        tvOutput =
                findViewById(
                        R.id.tvOutput
                );

        etInput =
                findViewById(
                        R.id.etInput
                );

        scrollView =
                findViewById(
                        R.id.scrollView
                );

        lvScripts =
                findViewById(
                        R.id.lvScripts
                );

        Button btnAdd =
                findViewById(
                        R.id.btnAdd
                );

        Button btnSend =
                findViewById(
                        R.id.btnSend
                );

        prefs =
                getSharedPreferences(
                        "script_prefs",
                        MODE_PRIVATE
                );

        // ========================================================
        // 恢复列表
        // ========================================================

        Set<String> savedScripts =
                prefs.getStringSet(
                        "scripts",
                        new HashSet<>()
                );

        synchronized (scriptList) {

            for (String savedPath :
                    savedScripts) {

                String normalized =
                        normalizeSavedPath(
                                savedPath
                        );

                if (normalized != null
                        && !scriptList.contains(
                        normalized
                )) {

                    scriptList.add(
                            normalized
                    );
                }
            }
        }

        // ========================================================
        // 添加内置文件
        // ========================================================

        addBuiltinScript(
                BUILTIN_KAIROS
        );

        addBuiltinScript(
                BUILTIN_TIME
        );

        // ========================================================
        // Adapter
        // ========================================================

        adapter =
                new ScriptAdapter();

        lvScripts.setAdapter(
                adapter
        );

        setupKeyboardListener();

        // ========================================================
        // 后台 Root 初始化
        // ========================================================

        new Thread(() -> {

            if (!checkRoot()) {

                showRootDialog();

                return;
            }

            appendText(
                    "[+] Root 权限正常\n"
            );

            if (prepareRuntimeDir()) {

                appendText(
                        "[+] 运行目录正常：\n"
                                + RUNTIME_DIR
                                + "\n"
                );

            } else {

                appendText(
                        "[!] 运行目录创建失败\n"
                );
            }

            // ----------------------------------------------------
            // 安装内置文件
            // ----------------------------------------------------

            installBuiltinAsset(
                    BUILTIN_KAIROS
            );

            installBuiltinAsset(
                    BUILTIN_TIME
            );

        }).start();

        // ========================================================
        // 添加文件
        // ========================================================

        btnAdd.setOnClickListener(v -> {

            Intent intent =
                    new Intent(
                            Intent.ACTION_GET_CONTENT
                    );

            intent.setType("*/*");

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            filePickerLauncher.launch(
                    intent
            );
        });

        // ========================================================
        // 输入 / 执行
        // ========================================================

        btnSend.setOnClickListener(v -> {

            String input =
                    etInput
                            .getText()
                            .toString();

            if (input.trim().isEmpty()) {

                return;
            }

            if (elfRunning
                    && process != null
                    && writer != null) {

                sendInputToElf(
                        input
                );

            } else {

                executeCommand(
                        input
                );
            }
        });
    }

    // ============================================================
    // Keyboard
    // ============================================================

    private void setupKeyboardListener() {

        final View rootView =
                findViewById(
                        android.R.id.content
                );

        rootView.getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> {

                    if (lvScripts == null) {

                        return;
                    }

                    Rect visibleRect =
                            new Rect();

                    rootView.getWindowVisibleDisplayFrame(
                            visibleRect
                    );

                    int rootHeight =
                            rootView
                                    .getRootView()
                                    .getHeight();

                    int visibleHeight =
                            visibleRect.bottom
                                    - visibleRect.top;

                    int keyboardHeight =
                            rootHeight
                                    - visibleHeight;

                    boolean nowVisible =
                            keyboardHeight
                                    > rootHeight * 0.15f;

                    if (nowVisible
                            == keyboardVisible) {

                        return;
                    }

                    keyboardVisible =
                            nowVisible;

                    setScriptListKeyboardMode(
                            keyboardVisible
                    );
                });
    }

    // ============================================================
    // 修改列表高度
    // ============================================================

    private void setScriptListKeyboardMode(
            boolean keyboardMode
    ) {

        if (lvScripts == null) {

            return;
        }

        ViewGroup.LayoutParams rawParams =
                lvScripts.getLayoutParams();

        if (!(rawParams instanceof
                ConstraintLayout.LayoutParams)) {

            return;
        }

        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams)
                        rawParams;

        if (keyboardMode) {

            params.height =
                    dpToPx(
                            SCRIPT_LIST_KEYBOARD_DP
                    );

            params.matchConstraintPercentHeight =
                    -1f;

        } else {

            params.height =
                    0;

            params.matchConstraintPercentHeight =
                    0.55f;
        }

        lvScripts.setLayoutParams(
                params
        );

        lvScripts.requestLayout();

        if (keyboardMode
                && scrollView != null) {

            scrollView.post(() ->
                    scrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        }
    }

    // ============================================================
    // dp -> px
    // ============================================================

    private int dpToPx(
            int dp
    ) {

        return (int) (
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density
                        + 0.5f
        );
    }

    // ============================================================
    // 添加内置文件
    // ============================================================

    private void addBuiltinScript(
            String assetName
    ) {

        String runtimePath =
                RUNTIME_DIR
                        + "/"
                        + assetName;

        synchronized (scriptList) {

            if (!scriptList.contains(
                    runtimePath
            )) {

                scriptList.add(
                        runtimePath
                );
            }
        }

        saveScripts();
    }

    // ============================================================
    // 安装 APK 内置 ELF
    // ============================================================

    private boolean installBuiltinAsset(
            String assetName
    ) {

        File tempFile =
                new File(
                        getFilesDir(),
                        "builtin_"
                                + assetName
                );

        try {

            if (!prepareRuntimeDir()) {

                return false;
            }

            appendText(
                    "[内置文件] 安装："
                            + assetName
                            + "\n"
            );

            // ----------------------------------------------------
            // APK assets
            // ----------------------------------------------------

            InputStream is =
                    getAssets()
                            .open(
                                    assetName
                            );

            FileOutputStream fos =
                    new FileOutputStream(
                            tempFile
                    );

            byte[] buffer =
                    new byte[8192];

            int len;

            while ((len =
                    is.read(buffer)) > 0) {

                fos.write(
                        buffer,
                        0,
                        len
                );
            }

            is.close();
            fos.close();

            if (!tempFile.exists()
                    || tempFile.length() == 0) {

                appendText(
                        "[内置文件] 文件为空："
                                + assetName
                                + "\n"
                );

                return false;
            }

            appendText(
                    "[内置文件] 大小："
                            + tempFile.length()
                            + " bytes\n"
            );

            // ----------------------------------------------------
            // Root 复制
            // ----------------------------------------------------

            String runtimePath =
                    RUNTIME_DIR
                            + "/"
                            + assetName;

            boolean copied =
                    copyFileAsRoot(
                            tempFile.getAbsolutePath(),
                            runtimePath
                    );

            if (!copied) {

                appendText(
                        "[内置文件] Root 复制失败："
                                + assetName
                                + "\n"
                );

                return false;
            }

            if (!chmod755(
                    runtimePath
            )) {

                appendText(
                        "[内置文件] chmod 失败："
                                + assetName
                                + "\n"
                );

                return false;
            }

            appendText(
                    "[+] 内置文件安装完成："
                            + runtimePath
                            + "\n"
            );

            return true;

        } catch (Exception e) {

            appendText(
                    "[内置文件] 安装异常："
                            + assetName
                            + "\n"
                            + safeMessage(e)
                            + "\n"
            );

            return false;

        } finally {

            try {

                if (tempFile.exists()) {

                    tempFile.delete();
                }

            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // ELF 输入
    // ============================================================

    private void sendInputToElf(
            String input
    ) {

        try {

            BufferedWriter currentWriter =
                    writer;

            Process currentProcess =
                    process;

            if (currentWriter == null
                    || currentProcess == null
                    || !elfRunning) {

                appendText(
                        "[输入通道尚未建立]\n"
                );

                return;
            }

            currentWriter.write(
                    input
            );

            currentWriter.newLine();

            currentWriter.flush();

            runOnUiThread(() ->
                    etInput.setText("")
            );

        } catch (Exception e) {

            appendText(
                    "[ELF 输入失败] "
                            + safeMessage(e)
                            + "\n"
            );
        }
    }

    // ============================================================
    // 普通 Root 命令
    // ============================================================

    private void executeCommand(
            String cmd
    ) {

        if (cmd == null
                || cmd.trim().isEmpty()) {

            return;
        }

        final String command =
                cmd.trim();

        appendText(
                "$ "
                        + command
                        + "\n"
        );

        runOnUiThread(() ->
                etInput.setText("")
        );

        new Thread(() -> {

            try {

                String finalCmd =
                        buildEnvironmentCommand(
                                command
                        );

                appendText(
                        "[执行]\n"
                                + finalCmd
                                + "\n"
                );

                ProcessBuilder pb =
                        new ProcessBuilder(
                                findSu(),
                                "-c",
                                finalCmd
                        );

                pb.redirectErrorStream(
                        true
                );

                Process p =
                        pb.start();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        p.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                char[] buffer =
                        new char[1024];

                int count;

                while ((count =
                        reader.read(buffer))
                        != -1) {

                    if (count <= 0) {

                        continue;
                    }

                    String raw =
                            new String(
                                    buffer,
                                    0,
                                    count
                            );

                    String clean =
                            cleanElfOutput(
                                    raw
                            );

                    if (!clean.isEmpty()) {

                        appendText(
                                clean
                        );
                    }
                }

                int exitCode =
                        p.waitFor();

                appendText(
                        "\n[exit "
                                + exitCode
                                + "]\n"
                );

            } catch (Exception e) {

                appendText(
                        "\n[执行失败] "
                                + safeMessage(e)
                                + "\n"
                );
            }

        }).start();
    }

    // ============================================================
    // 构建环境
    // ============================================================

    private String buildEnvironmentCommand(
            String command
    ) {

        return
                "export PATH="
                        + shellQuote(
                                RUNTIME_DIR
                                        + ":/data/local/tmp"
                                        + ":/system/bin"
                                        + ":/system/xbin"
                                        + ":/vendor/bin"
                        )
                        + ":$PATH; "
                        + "export HOME="
                        + shellQuote(
                                RUNTIME_DIR
                        )
                        + "; "
                        + "export TMPDIR="
                        + shellQuote(
                                RUNTIME_DIR
                        )
                        + "; "
                        + command;
    }

    // ============================================================
    // 找 su
    // ============================================================

    private String findSu() {

        String[] suPaths = {

                "/system/bin/su",

                "/system/xbin/su",

                "/sbin/su",

                "/debug_ramdisk/su"
        };

        for (String path :
                suPaths) {

            if (new File(path).exists()) {

                return path;
            }
        }

        return "su";
    }

    // ============================================================
    // Root 检查
    // ============================================================

    private boolean checkRoot() {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "id"
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            int exitCode =
                    p.waitFor();

            return exitCode == 0
                    && output != null
                    && output.contains(
                            "uid=0"
                    );

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // Root Dialog
    // ============================================================

    private void showRootDialog() {

        runOnUiThread(() -> {

            if (isFinishing()
                    || isDestroyed()) {

                return;
            }

            new AlertDialog.Builder(
                    MainActivity.this
            )
                    .setTitle(
                            "需要 Root 权限"
                    )
                    .setMessage(
                            "本软件需要 Root 权限才能执行 ELF。\n\n"
                                    + "请在 KernelSU / Magisk 中允许本应用，"
                                    + "然后点击「重试」。"
                    )
                    .setPositiveButton(
                            "重试",
                            (dialog, which) -> {

                                new Thread(() -> {

                                    if (checkRoot()) {

                                        appendText(
                                                "[+] Root 权限已恢复\n"
                                        );

                                        prepareRuntimeDir();

                                        String path =
                                                pendingScriptPath;

                                        pendingScriptPath =
                                                null;

                                        if (path != null) {

                                            runElfReal(
                                                    path
                                            );
                                        }

                                    } else {

                                        showRootDialog();
                                    }

                                }).start();
                            }
                    )
                    .setNegativeButton(
                            "退出",
                            (dialog, which) ->
                                    finish()
                    )
                    .setCancelable(false)
                    .show();
        });
    }

    // ============================================================
    // Shell Quote
    // ============================================================

    private String shellQuote(
            String value
    ) {

        if (value == null) {

            return "''";
        }

        return "'"
                + value.replace(
                        "'",
                        "'\\''"
                )
                + "'";
    }

    // ============================================================
    // 运行 ELF
    // ============================================================

    private void runElf(
            String scriptPath
    ) {

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                appendText(
                        "[ELF] 没有 Root，等待授权\n"
                );

                showRootDialog();

                return;
            }

            runElfReal(
                    scriptPath
            );

        }).start();
    }

    // ============================================================
    // 真正运行 ELF
    // ============================================================

    private void runElfReal(
            String scriptPath
    ) {

        // --------------------------------------------------------
        // 先停止旧 ELF
        // --------------------------------------------------------

        stopCurrentElf();

        try {

            // ----------------------------------------------------
            // Root
            // ----------------------------------------------------

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                appendText(
                        "[ELF] Root 权限丢失\n"
                );

                showRootDialog();

                return;
            }

            // ----------------------------------------------------
            // Runtime
            // ----------------------------------------------------

            if (!prepareRuntimeDir()) {

                appendText(
                        "[ELF] 无法创建运行目录\n"
                );

                return;
            }

            // ----------------------------------------------------
            // 路径规范化
            // ----------------------------------------------------

            String runtimePath =
                    normalizeSavedPath(
                            scriptPath
                    );

            if (runtimePath == null) {

                appendText(
                        "[ELF] 无效路径\n"
                );

                return;
            }

            File elf =
                    new File(
                            runtimePath
                    );

            String fileName =
                    elf.getName();

            // ----------------------------------------------------
            // 内置文件
            // ----------------------------------------------------

            if (BUILTIN_KAIROS.equals(
                    fileName
            )
                    || BUILTIN_TIME.equals(
                    fileName
            )) {

                if (!elf.exists()
                        || elf.length() == 0) {

                    appendText(
                            "[ELF] 内置文件不存在，重新安装："
                                    + fileName
                                    + "\n"
                    );

                    if (!installBuiltinAsset(
                            fileName
                    )) {

                        appendText(
                                "[ELF] 内置文件安装失败\n"
                        );

                        return;
                    }
                }
            }

            // ----------------------------------------------------
            // 检查 ELF
            // ----------------------------------------------------

            if (!elf.exists()) {

                appendText(
                        "[ELF] 文件不存在：\n"
                                + runtimePath
                                + "\n"
                );

                return;
            }

            if (!elf.isFile()) {

                appendText(
                        "[ELF] 不是普通文件：\n"
                                + runtimePath
                                + "\n"
                );

                return;
            }

            if (elf.length() == 0) {

                appendText(
                        "[ELF] 文件大小为 0：\n"
                                + runtimePath
                                + "\n"
                );

                return;
            }

            appendText(
                    "\n================================\n"
            );

            appendText(
                    "[ELF] 准备启动\n"
            );

            appendText(
                    "[ELF] 路径："
                            + runtimePath
                            + "\n"
            );

            appendText(
                    "[ELF] 大小："
                            + elf.length()
                            + " bytes\n"
            );

            // ----------------------------------------------------
            // chmod
            // ----------------------------------------------------

            if (!chmod755(
                    runtimePath
            )) {

                appendText(
                        "[ELF] chmod 755 失败\n"
                );

                return;
            }

            // ----------------------------------------------------
            // 文件信息
            // ----------------------------------------------------

            String lsOutput =
                    rootLs(
                            runtimePath
                    );

            if (lsOutput != null) {

                appendText(
                        "[ELF] 文件权限："
                                + lsOutput.trim()
                                + "\n"
                );
            }

            // ----------------------------------------------------
            // ELF Magic
            // ----------------------------------------------------

            String magic =
                    readFileMagicAsRoot(
                            runtimePath
                    );

            if (magic != null) {

                appendText(
                        "[ELF] Magic："
                                + magic
                                + "\n"
                );

                if (!magic.startsWith(
                        "7f 45 4c 46"
                )) {

                    appendText(
                            "[警告] 这个文件不是标准 ELF 文件\n"
                    );
                }
            }

            // ----------------------------------------------------
            // BusyBox
            //
            // 不再用：
            //
            // busybox script -q -c 'exec ELF'
            //
            // ELF 直接启动。
            //
            // ----------------------------------------------------

            extractAndPrepareBusybox();

            // ----------------------------------------------------
            // 工作目录
            // ----------------------------------------------------

            String elfDir =
                    elf.getParent();

            if (elfDir == null) {

                elfDir =
                        RUNTIME_DIR;
            }

            // ----------------------------------------------------
            // 环境
            // ----------------------------------------------------

            String env =
                    "export PATH="
                            + shellQuote(
                                    RUNTIME_DIR
                                            + ":/data/local/tmp"
                                            + ":/system/bin"
                                            + ":/system/xbin"
                                            + ":/vendor/bin"
                            )
                            + ":$PATH; "
                            + "export HOME="
                            + shellQuote(
                                    RUNTIME_DIR
                            )
                            + "; "
                            + "export TMPDIR="
                            + shellQuote(
                                    RUNTIME_DIR
                            )
                            + "; "
                            + "export LD_LIBRARY_PATH="
                            + shellQuote(
                                    "/system/lib64:/vendor/lib64"
                            )
                            + ":$LD_LIBRARY_PATH; "
                            + "cd "
                            + shellQuote(
                                    elfDir
                            )
                            + "; ";

            // ----------------------------------------------------
            // ELF 本体
            //
            // 关键：
            //
            // exec '/path/to/ELF'
            //
            // 不经过 busybox script。
            // ----------------------------------------------------

            String elfCommand =
                    "exec "
                            + shellQuote(
                                    elf.getAbsolutePath()
                            );

            String command =
                    env
                            + elfCommand;

            appendText(
                    "[ELF] linker："
                            + "/system/bin/linker64"
                            + "\n"
            );

            appendText(
                    "[ELF] 执行方式：直接 exec\n"
            );

            appendText(
                    "[ELF] Shell command：\n"
                            + command
                            + "\n"
            );

            // ----------------------------------------------------
            // Process
            // ----------------------------------------------------

            ProcessBuilder pb =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    );

            /*
             * 不使用 redirectErrorStream(true)
             *
             * 因为我们分别读取 stdout/stderr，
             * 这样可以更准确看到 linker 错误。
             */
            pb.redirectErrorStream(
                    false
            );

            try {

                pb.directory(
                        new File(
                                elfDir
                        )
                );

            } catch (Exception ignored) {
            }

            process =
                    pb.start();

            final Process currentProcess =
                    process;

            // ----------------------------------------------------
            // stdin
            // ----------------------------------------------------

            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    currentProcess
                                            .getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            elfRunning =
                    true;

            appendText(
                    "[+] ELF Process 已启动\n"
            );

            appendText(
                    "[+] PID："
                            + getProcessPid(
                                    currentProcess
                            )
                            + "\n"
            );

            appendText(
                    "================================\n\n"
            );

            // ====================================================
            // stdout
            // ====================================================

            Thread stdoutThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getInputStream(),
                                            StandardCharsets.UTF_8
                                    );

                            char[] buffer =
                                    new char[1024];

                            int count;

                            while ((count =
                                    reader.read(buffer))
                                    != -1) {

                                if (count <= 0) {

                                    continue;
                                }

                                String raw =
                                        new String(
                                                buffer,
                                                0,
                                                count
                                        );

                                String clean =
                                        cleanElfOutput(
                                                raw
                                        );

                                if (!clean.isEmpty()) {

                                    appendText(
                                            clean
                                    );
                                }
                            }

                        } catch (Exception e) {

                            if (elfRunning) {

                                appendText(
                                        "[stdout 读取失败] "
                                                + safeMessage(e)
                                                + "\n"
                                );
                            }
                        }

                    });

            stdoutThread.setName(
                    "ELF-stdout"
            );

            // ====================================================
            // stderr
            // ====================================================

            Thread stderrThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess
                                                    .getErrorStream(),
                                            StandardCharsets.UTF_8
                                    );

                            char[] buffer =
                                    new char[1024];

                            int count;

                            while ((count =
                                    reader.read(buffer))
                                    != -1) {

                                if (count <= 0) {

                                    continue;
                                }

                                String raw =
                                        new String(
                                                buffer,
                                                0,
                                                count
                                        );

                                String clean =
                                        cleanElfOutput(
                                                raw
                                        );

                                if (!clean.isEmpty()) {

                                    appendText(
                                            clean
                                    );
                                }
                            }

                        } catch (Exception e) {

                            if (elfRunning) {

                                appendText(
                                        "[stderr 读取失败] "
                                                + safeMessage(e)
                                                + "\n"
                                );
                            }
                        }

                    });

            stderrThread.setName(
                    "ELF-stderr"
            );

            stdoutThread.start();

            stderrThread.start();

            // ====================================================
            // 等待
            // ====================================================

            new Thread(() -> {

                try {

                    int exitCode =
                            currentProcess.waitFor();

                    try {

                        stdoutThread.join(
                                1500
                        );

                    } catch (Exception ignored) {
                    }

                    try {

                        stderrThread.join(
                                1500
                        );

                    } catch (Exception ignored) {
                    }

                    final int code =
                            exitCode;

                    appendText(
                            "\n[ELF exit "
                                    + code
                                    + "]\n"
                    );

                } catch (Exception e) {

                    appendText(
                            "\n[ELF wait 失败] "
                                    + safeMessage(e)
                                    + "\n"
                    );

                } finally {

                    if (process ==
                            currentProcess) {

                        writer = null;

                        process = null;

                        elfRunning =
                                false;
                    }
                }

            }, "ELF-waiter").start();

        } catch (Exception e) {

            writer = null;

            process = null;

            elfRunning =
                    false;

            appendText(
                    "\n[ELF 启动失败]\n"
                            + safeMessage(e)
                            + "\n"
            );
        }
    }

    // ============================================================
    // 获取 Process PID
    // ============================================================

    private long getProcessPid(
            Process p
    ) {

        if (p == null) {

            return -1;
        }

        try {

            return p.pid();

        } catch (Throwable ignored) {

            return -1;
        }
    }

    // ============================================================
    // 停止 ELF
    // ============================================================

    private void stopCurrentElf() {

        elfRunning =
                false;

        try {

            BufferedWriter currentWriter =
                    writer;

            if (currentWriter != null) {

                currentWriter.close();
            }

        } catch (Exception ignored) {
        }

        writer = null;

        try {

            Process currentProcess =
                    process;

            if (currentProcess != null) {

                currentProcess.destroy();

                try {

                    if (currentProcess.isAlive()) {

                        currentProcess.destroyForcibly();
                    }

                } catch (Exception ignored) {
                }
            }

        } catch (Exception ignored) {
        }

        process = null;
    }

    // ============================================================
    // 创建运行目录
    // ============================================================

    private boolean prepareRuntimeDir() {

        try {

            String command =
                    "mkdir -p "
                            + shellQuote(
                                    RUNTIME_DIR
                            )
                            + " && chmod 755 "
                            + shellQuote(
                                    RUNTIME_DIR
                            );

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            int exitCode =
                    p.waitFor();

            if (exitCode != 0) {

                appendText(
                        "[运行目录创建失败] "
                                + output
                                + "\n"
                );

                return false;
            }

            return true;

        } catch (Exception e) {

            appendText(
                    "[运行目录异常] "
                            + safeMessage(e)
                            + "\n"
            );

            return false;
        }
    }

    // ============================================================
    // Root 复制文件
    // ============================================================

    private boolean copyFileAsRoot(
            String source,
            String destination
    ) {

        try {

            String command =
                    "mkdir -p "
                            + shellQuote(
                                    RUNTIME_DIR
                            )
                            + "; "
                            + "cat "
                            + shellQuote(
                                    source
                            )
                            + " > "
                            + shellQuote(
                                    destination
                            )
                            + "; "
                            + "chmod 755 "
                            + shellQuote(
                                    destination
                            );

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            int exitCode =
                    p.waitFor();

            if (exitCode != 0) {

                appendText(
                        "[Root复制失败] "
                                + output
                                + "\n"
                );

                return false;
            }

            // 再检查目标
            File destinationFile =
                    new File(
                            destination
                    );

            if (!destinationFile.exists()) {

                appendText(
                        "[Root复制失败] 目标文件不存在\n"
                                + destination
                                + "\n"
                );

                return false;
            }

            return true;

        } catch (Exception e) {

            appendText(
                    "[Root复制异常] "
                            + safeMessage(e)
                            + "\n"
            );

            return false;
        }
    }

    // ============================================================
    // chmod 755
    // ============================================================

    private boolean chmod755(
            String path
    ) {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "chmod 755 "
                                    + shellQuote(
                                            path
                                    )
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            int exitCode =
                    p.waitFor();

            if (exitCode != 0) {

                appendText(
                        "[chmod失败] "
                                + path
                                + "\n"
                                + output
                                + "\n"
                );

                return false;
            }

            return true;

        } catch (Exception e) {

            appendText(
                    "[chmod异常] "
                            + safeMessage(e)
                            + "\n"
            );

            return false;
        }
    }

    // ============================================================
    // Root ls
    // ============================================================

    private String rootLs(
            String path
    ) {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "ls -l "
                                    + shellQuote(
                                            path
                                    )
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            p.waitFor();

            return output;

        } catch (Exception e) {

            return null;
        }
    }

    // ============================================================
    // 读取 ELF 文件头
    // ============================================================

    private String readFileMagicAsRoot(
            String path
    ) {

        try {

            String command =
                    "od -An -tx1 -N 4 "
                            + shellQuote(
                                    path
                            );

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            p.waitFor();

            if (output == null) {

                return null;
            }

            return output
                    .trim()
                    .replaceAll(
                            "\\s+",
                            " "
                    );

        } catch (Exception e) {

            return null;
        }
    }

    // ============================================================
    // APK BusyBox
    // ============================================================

    private boolean extractAndPrepareBusybox() {

        File tempFile =
                new File(
                        getFilesDir(),
                        "busybox_temp"
                );

        try {

            InputStream is =
                    getAssets()
                            .open(
                                    BUSYBOX_ASSET
                            );

            FileOutputStream fos =
                    new FileOutputStream(
                            tempFile
                    );

            byte[] buffer =
                    new byte[8192];

            int len;

            while ((len =
                    is.read(buffer)) > 0) {

                fos.write(
                        buffer,
                        0,
                        len
                );
            }

            is.close();
            fos.close();

            if (!tempFile.exists()
                    || tempFile.length() < 100000) {

                appendText(
                        "[BusyBox] assets/busybox 文件异常\n"
                );

                return false;
            }

            if (!prepareRuntimeDir()) {

                return false;
            }

            String destination =
                    RUNTIME_BUSYBOX;

            String command =
                    "cat "
                            + shellQuote(
                                    tempFile.getAbsolutePath()
                            )
                            + " > "
                            + shellQuote(
                                    destination
                            )
                            + "; chmod 755 "
                            + shellQuote(
                                    destination
                            );

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(
                            p.getInputStream()
                    );

            int exit =
                    p.waitFor();

            if (exit != 0) {

                appendText(
                        "[BusyBox] 安装失败\n"
                                + output
                );

                return false;
            }

            busyboxFile =
                    new File(
                            RUNTIME_BUSYBOX
                    );

            if (!busyboxFile.exists()
                    || busyboxFile.length() == 0) {

                appendText(
                        "[BusyBox] 文件不存在\n"
                );

                return false;
            }

            return true;

        } catch (Exception e) {

            appendText(
                    "[BusyBox] 初始化异常："
                            + safeMessage(e)
                            + "\n"
            );

            return false;

        } finally {

            try {

                if (tempFile.exists()) {

                    tempFile.delete();
                }

            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // BusyBox applet
    // ============================================================

    private boolean hasBusyboxApplet(
            String appletList,
            String wanted
    ) {

        if (appletList == null
                || wanted == null) {

            return false;
        }

        String[] applets =
                appletList.split(
                        "\\s+"
                );

        for (String applet :
                applets) {

            if (wanted.equals(
                    applet.trim()
            )) {

                return true;
            }
        }

        return false;
    }

    // ============================================================
    // readAll
    // ============================================================

    private String readAll(
            InputStream inputStream
    ) {

        StringBuilder result =
                new StringBuilder();

        if (inputStream == null) {

            return "";
        }

        try {

            InputStreamReader reader =
                    new InputStreamReader(
                            inputStream,
                            StandardCharsets.UTF_8
                    );

            char[] buffer =
                    new char[1024];

            int count;

            while ((count =
                    reader.read(buffer))
                    != -1) {

                if (count > 0) {

                    result.append(
                            buffer,
                            0,
                            count
                    );
                }
            }

        } catch (Exception ignored) {
        }

        return result.toString();
    }

    // ============================================================
    // 清理输出
    // ============================================================

    private String cleanElfOutput(
            String text
    ) {

        if (text == null
                || text.length() == 0) {

            return "";
        }

        text =
                text.replaceAll(
                        "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                        ""
                );

        text =
                text.replaceAll(
                        "\\[(?:[0-9;?]+)m",
                        ""
                );

        text =
                text.replace(
                        "公益倒卖死全家",
                        ""
                );

        return text;
    }

    // ============================================================
    // 获取文件名
    // ============================================================

    private String getFileName(
            Uri uri
    ) {

        String result =
                null;

        if ("content".equals(
                uri.getScheme()
        )) {

            try (
                    Cursor cursor =
                            getContentResolver()
                                    .query(
                                            uri,
                                            null,
                                            null,
                                            null,
                                            null
                                    )
            ) {

                if (cursor != null
                        && cursor.moveToFirst()) {

                    int nameIndex =
                            cursor.getColumnIndex(
                                    OpenableColumns
                                            .DISPLAY_NAME
                            );

                    if (nameIndex != -1) {

                        result =
                                cursor.getString(
                                        nameIndex
                                );
                    }
                }

            } catch (Exception ignored) {
            }
        }

        if (result == null) {

            result =
                    uri.getPath();

            if (result != null) {

                int cut =
                        result.lastIndexOf('/');

                if (cut != -1) {

                    result =
                            result.substring(
                                    cut + 1
                            );
                }
            }
        }

        return result;
    }

    // ============================================================
    // 文件名清理
    // ============================================================

    private String sanitizeFileName(
            String name
    ) {

        if (name == null
                || name.isEmpty()) {

            return "script_"
                    + System.currentTimeMillis()
                    + ".sh";
        }

        name =
                name.replace(
                        "/",
                        "_"
                );

        name =
                name.replace(
                        "\\",
                        "_"
                );

        name =
                name.replace(
                        "\u0000",
                        "_"
                );

        if (".".equals(name)
                || "..".equals(name)) {

            name =
                    "script_"
                            + System.currentTimeMillis()
                            + ".sh";
        }

        return name;
    }

    // ============================================================
    // 路径迁移
    // ============================================================

    private String normalizeSavedPath(
            String savedPath
    ) {

        if (savedPath == null
                || savedPath.trim().isEmpty()) {

            return null;
        }

        savedPath =
                savedPath.trim();

        // --------------------------------------------------------
        // 新路径
        // --------------------------------------------------------

        if (savedPath.startsWith(
                RUNTIME_DIR + "/"
        )) {

            return savedPath;
        }

        // --------------------------------------------------------
        // 旧路径
        // --------------------------------------------------------

        String fileName =
                new File(
                        savedPath
                ).getName();

        if (fileName == null
                || fileName.isEmpty()) {

            return null;
        }

        return RUNTIME_DIR
                + "/"
                + fileName;
    }

    // ============================================================
    // 保存
    // ============================================================

    private void saveScripts() {

        if (prefs == null) {

            return;
        }

        synchronized (scriptList) {

            prefs.edit()
                    .putStringSet(
                            "scripts",
                            new HashSet<>(
                                    scriptList
                            )
                    )
                    .apply();
        }
    }

    // ============================================================
    // Script Adapter
    // ============================================================

    private class ScriptAdapter
            extends ArrayAdapter<String> {

        ScriptAdapter() {

            super(
                    MainActivity.this,
                    0,
                    scriptList
            );
        }

        @NonNull
        @Override
        public View getView(
                int position,
                View convertView,
                @NonNull ViewGroup parent
        ) {

            if (convertView == null) {

                convertView =
                        LayoutInflater
                                .from(
                                        getContext()
                                )
                                .inflate(
                                        R.layout.item_script,
                                        parent,
                                        false
                                );
            }

            String path;

            synchronized (scriptList) {

                if (position < 0
                        || position >= scriptList.size()) {

                    return convertView;
                }

                path =
                        scriptList.get(
                                position
                        );
            }

            String fileName =
                    new File(path)
                            .getName();

            TextView tvName =
                    convertView.findViewById(
                            R.id.tvScriptName
                    );

            Button btnRun =
                    convertView.findViewById(
                            R.id.btnRun
                    );

            Button btnDelete =
                    convertView.findViewById(
                            R.id.btnDelete
                    );

            if (tvName != null) {

                tvName.setText(
                        fileName
                );
            }

            if (btnRun != null) {

                btnRun.setOnClickListener(
                        v -> runElf(path)
                );
            }

            if (btnDelete != null) {

                btnDelete.setOnClickListener(
                        v -> {

                            String deletePath =
                                    null;

                            synchronized (scriptList) {

                                if (position >= 0
                                        && position
                                        < scriptList.size()) {

                                    deletePath =
                                            scriptList.remove(
                                                    position
                                            );
                                }
                            }

                            // ------------------------------------
                            // 删除运行目录中的实际文件
                            // ------------------------------------

                            if (deletePath != null) {

                                final String target =
                                        deletePath;

                                new Thread(() -> {

                                    try {

                                        if (target.startsWith(
                                                RUNTIME_DIR + "/"
                                        )) {

                                            Process p =
                                                    new ProcessBuilder(
                                                            findSu(),
                                                            "-c",
                                                            "rm -f "
                                                                    + shellQuote(
                                                                    target
                                                            )
                                                    )
                                                            .redirectErrorStream(
                                                                    true
                                                            )
                                                            .start();

                                            p.waitFor();
                                        }

                                    } catch (Exception ignored) {
                                    }

                                }).start();
                            }

                            notifyDataSetChanged();

                            saveScripts();
                        }
                );
            }

            return convertView;
        }
    }

    // ============================================================
    // 输出
    // ============================================================

    private void appendText(
            String text
    ) {

        if (text == null
                || text.length() == 0) {

            return;
        }

        runOnUiThread(() -> {

            if (tvOutput == null) {

                return;
            }

            tvOutput.append(
                    text
            );

            if (scrollView != null) {

                scrollView.post(() ->
                        scrollView.fullScroll(
                                View.FOCUS_DOWN
                        )
                );
            }
        });
    }

    // ============================================================
    // Exception message
    // ============================================================

    private String safeMessage(
            Exception e
    ) {

        if (e == null) {

            return "unknown error";
        }

        String msg =
                e.getMessage();

        if (msg == null
                || msg.isEmpty()) {

            return e.toString();
        }

        return msg;
    }

    // ============================================================
    // Activity destroy
    // ============================================================

    @Override
    protected void onDestroy() {

        stopCurrentElf();

        super.onDestroy();
    }
}


