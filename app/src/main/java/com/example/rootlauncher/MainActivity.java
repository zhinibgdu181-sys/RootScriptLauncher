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
import java.io.FileInputStream;
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
    // Script list
    // ============================================================

    private final ArrayList<String> scriptList =
            new ArrayList<>();

    private ScriptAdapter adapter;

    // ============================================================
    // Current process
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
    // Keyboard
    // ============================================================

    private boolean keyboardVisible = false;

    private static final int SCRIPT_LIST_KEYBOARD_DP = 120;

    // ============================================================
    // Builtin
    // ============================================================

    private static final String BUILTIN_KAIROS =
            "Kairos_Driver_Loader_Release_90f76e9.sh";

    private static final String BUILTIN_TIME =
            "TIME_Cloud_Loader_Release_1732727.sh";

    // ============================================================
    // Runtime
    // ============================================================

    private static final String RUNTIME_DIR =
            "/data/local/tmp/com.example.rootlauncher/files";

    // ============================================================
    // File picker
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
                                    "file_"
                                            + System.currentTimeMillis();
                        }

                        final String finalDisplayName =
                                sanitizeFileName(displayName);

                        new Thread(() -> {

                            File tempFile = null;

                            try {

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

                                try {
                                    is.close();
                                } catch (Exception ignored) {
                                }

                                try {
                                    fos.close();
                                } catch (Exception ignored) {
                                }

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

                                ElfInfo localInfo =
                                        inspectElfFile(
                                                tempFile
                                        );

                                if (localInfo.isElf) {

                                    appendText(
                                            "[+] 检测到 ELF\n"
                                    );

                                    appendText(
                                            "[ELF] Class："
                                                    + localInfo.elfClass
                                                    + "\n"
                                    );

                                    appendText(
                                            "[ELF] Machine："
                                                    + localInfo.machine
                                                    + "\n"
                                    );

                                    appendText(
                                            "[ELF] OS ABI："
                                                    + localInfo.osAbi
                                                    + "\n"
                                    );

                                    if (localInfo.interpreter != null
                                            && !localInfo.interpreter.isEmpty()) {

                                        appendText(
                                                "[ELF] PT_INTERP："
                                                        + localInfo.interpreter
                                                        + "\n"
                                        );
                                    }

                                } else if (localInfo.isShebang) {

                                    appendText(
                                            "[+] 检测到 Shell 脚本\n"
                                    );

                                    if (localInfo.shebang != null) {

                                        appendText(
                                                "[Script] Interpreter："
                                                        + localInfo.shebang
                                                        + "\n"
                                        );
                                    }

                                } else {

                                    appendText(
                                            "[!] 这不是标准 ELF 文件\n"
                                    );
                                }

                                if (!checkRoot()) {

                                    appendText(
                                            "[添加失败] 当前没有 Root 权限\n"
                                    );

                                    return;
                                }

                                if (!prepareRuntimeDir()) {

                                    appendText(
                                            "[添加失败] 无法创建运行目录\n"
                                    );

                                    return;
                                }

                                String runtimePath =
                                        RUNTIME_DIR
                                                + "/"
                                                + finalDisplayName;

                                if (!copyFileAsRoot(
                                        tempFile.getAbsolutePath(),
                                        runtimePath
                                )) {

                                    appendText(
                                            "[添加失败] 无法复制到运行目录\n"
                                    );

                                    return;
                                }

                                if (!chmod755(runtimePath)) {

                                    appendText(
                                            "[添加失败] chmod 755 失败\n"
                                    );

                                    return;
                                }

                                ElfInfo rootInfo =
                                        inspectElfAsRoot(
                                                runtimePath
                                        );

                                if (rootInfo.isElf) {

                                    appendText(
                                            "[ELF] Class："
                                                    + rootInfo.elfClass
                                                    + "\n"
                                    );

                                    appendText(
                                            "[ELF] Machine："
                                                    + rootInfo.machine
                                                    + "\n"
                                    );

                                    if (rootInfo.interpreter != null
                                            && !rootInfo.interpreter.isEmpty()) {

                                        appendText(
                                                "[ELF] Interpreter："
                                                        + rootInfo.interpreter
                                                        + "\n"
                                        );
                                    }
                                }

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

        addBuiltinScript(
                BUILTIN_KAIROS
        );

        addBuiltinScript(
                BUILTIN_TIME
        );

        adapter =
                new ScriptAdapter();

        lvScripts.setAdapter(
                adapter
        );

        setupKeyboardListener();

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

            installBuiltinAsset(
                    BUILTIN_KAIROS
            );

            installBuiltinAsset(
                    BUILTIN_TIME
            );

        }).start();

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
    // List height
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
    // dp
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
    // Builtin list
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
    // Install builtin
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

            ElfInfo info =
                    inspectElfFile(
                            tempFile
                    );

            if (info.isElf) {

                appendText(
                        "[内置 ELF] "
                                + info.elfClass
                                + " / "
                                + info.machine
                                + "\n"
                );

                if (info.interpreter != null
                        && !info.interpreter.isEmpty()) {

                    appendText(
                            "[内置 ELF] PT_INTERP："
                                    + info.interpreter
                                    + "\n"
                    );
                }

            } else if (info.isShebang) {

                appendText(
                        "[内置文件] 检测到 Shell 脚本\n"
                );

            } else {

                appendText(
                        "[内置文件] 警告：不是 ELF / shebang\n"
                );
            }

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
    // ELF input
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
    // Normal root command
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
    // Environment for shell commands
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
    // su
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
    // Root
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
    // Root dialog
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
    // Shell quote
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
    // Run
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
    // REAL ELF RUNNER
    // ============================================================

    private void runElfReal(
            String scriptPath
    ) {

        stopCurrentElf();

        try {

            if (!checkRoot()) {

                pendingScriptPath =
                        scriptPath;

                appendText(
                        "[ELF] Root 权限丢失\n"
                );

                showRootDialog();

                return;
            }

            if (!prepareRuntimeDir()) {

                appendText(
                        "[ELF] 无法创建运行目录\n"
                );

                return;
            }

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

            File target =
                    new File(
                            runtimePath
                    );

            String fileName =
                    target.getName();

            if (BUILTIN_KAIROS.equals(
                    fileName
            )
                    || BUILTIN_TIME.equals(
                    fileName
            )) {

                if (!target.exists()
                        || target.length() == 0) {

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

            if (!target.exists()) {

                appendText(
                        "[ELF] 文件不存在：\n"
                                + runtimePath
                                + "\n"
                );

                return;
            }

            if (!target.isFile()) {

                appendText(
                        "[ELF] 不是普通文件：\n"
                                + runtimePath
                                + "\n"
                );

                return;
            }

            if (target.length() == 0) {

                appendText(
                        "[ELF] 文件大小为 0\n"
                );

                return;
            }

            appendText(
                    "\n================================\n"
            );

            appendText(
                    "[EXEC] 准备启动\n"
            );

            appendText(
                    "[EXEC] Path："
                            + runtimePath
                            + "\n"
            );

            appendText(
                    "[EXEC] Size："
                            + target.length()
                            + " bytes\n"
            );

            if (!chmod755(
                    runtimePath
            )) {

                appendText(
                        "[EXEC] chmod 755 失败\n"
                );

                return;
            }

            ElfInfo info =
                    inspectElfAsRoot(
                            runtimePath
                    );

            if (info == null) {

                appendText(
                        "[EXEC] 无法读取文件信息\n"
                );

                return;
            }

            if (info.isElf) {

                appendText(
                        "[ELF] Magic：7f 45 4c 46\n"
                );

                appendText(
                        "[ELF] Class："
                                + info.elfClass
                                + "\n"
                );

                appendText(
                        "[ELF] Machine："
                                + info.machine
                                + "\n"
                );

                appendText(
                        "[ELF] OS ABI："
                                + info.osAbi
                                + "\n"
                );

                if (info.interpreter != null
                        && !info.interpreter.isEmpty()) {

                    appendText(
                            "[ELF] PT_INTERP："
                                    + info.interpreter
                                    + "\n"
                    );

                    if (!fileExistsAsRoot(
                            info.interpreter
                    )) {

                        appendText(
                                "[警告] ELF 指定的 interpreter 不存在：\n"
                                        + info.interpreter
                                        + "\n"
                        );
                    } else {

                        appendText(
                                "[+] ELF interpreter 存在\n"
                        );
                    }
                }

            } else if (info.isShebang) {

                appendText(
                        "[EXEC] 检测到 Shell 脚本\n"
                );

                if (info.shebang != null) {

                    appendText(
                            "[Script] Interpreter："
                                    + info.shebang
                                    + "\n"
                    );
                }

            } else {

                appendText(
                        "[错误] 文件既不是 ELF，也不是 Shell 脚本\n"
                );

                return;
            }

            String lsOutput =
                    rootLs(
                            runtimePath
                    );

            if (lsOutput != null) {

                appendText(
                        "[EXEC] 文件权限："
                                + lsOutput.trim()
                                + "\n"
                );
            }

            String workDir =
                    target.getParent();

            if (workDir == null
                    || workDir.isEmpty()) {

                workDir =
                        RUNTIME_DIR;
            }

            StringBuilder env =
                    new StringBuilder();

            env.append(
                    "export PATH="
            );

            env.append(
                    shellQuote(
                            RUNTIME_DIR
                                    + ":/data/local/tmp"
                                    + ":/system/bin"
                                    + ":/system/xbin"
                                    + ":/vendor/bin"
                    )
            );

            env.append(
                    ":$PATH; "
            );

            env.append(
                    "export HOME="
            );

            env.append(
                    shellQuote(
                            RUNTIME_DIR
                    )
            );

            env.append(
                    "; "
            );

            env.append(
                    "export TMPDIR="
            );

            env.append(
                    shellQuote(
                            RUNTIME_DIR
                    )
            );

            env.append(
                    "; "
            );

            env.append(
                    "cd "
            );

            env.append(
                    shellQuote(
                            workDir
                    )
            );

            env.append(
                    "; "
            );

            String command;

            if (info.isElf) {

                command =
                        env.toString()
                                + "exec "
                                + shellQuote(
                                target.getAbsolutePath()
                        );

                appendText(
                        "[ELF] 执行方式：kernel direct exec\n"
                );

                if (info.elfClass != null) {

                    appendText(
                            "[ELF] 位数："
                                    + info.elfClass
                                    + "\n"
                    );
                }

                if (info.machine != null) {

                    appendText(
                            "[ELF] 架构："
                                    + info.machine
                                    + "\n"
                    );
                }

                if (info.interpreter != null) {

                    appendText(
                            "[ELF] Kernel interpreter："
                                    + info.interpreter
                                    + "\n"
                    );
                }

            } else {

                String interpreter =
                        resolveScriptInterpreter(
                                info.shebang
                        );

                if (interpreter == null) {

                    appendText(
                            "[脚本] 无法找到 interpreter\n"
                    );

                    return;
                }

                command =
                        env.toString()
                                + "exec "
                                + shellQuote(
                                interpreter
                        )
                                + " "
                                + shellQuote(
                                target.getAbsolutePath()
                        );

                appendText(
                        "[脚本] 执行："
                                + interpreter
                                + "\n"
                );
            }

            appendText(
                    "[EXEC] Shell command：\n"
                            + command
                            + "\n"
            );

            ProcessBuilder pb =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    );

            pb.redirectErrorStream(
                    false
            );

            try {

                pb.directory(
                        new File(
                                workDir
                        )
                );

            } catch (Exception ignored) {
            }

            process =
                    pb.start();

            final Process currentProcess =
                    process;

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
                    "[+] Process 已启动\n"
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

                    appendText(
                            "\n[PROCESS exit "
                                    + exitCode
                                    + "]\n"
                    );

                } catch (Exception e) {

                    appendText(
                            "\n[PROCESS wait 失败] "
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
    // PID
    // ============================================================

    private long getProcessPid(
            Process p
    ) {

        // 当前 Android/Java 编译环境不提供
        // Process.pid()，因此不调用该 API。
        // 保留方法以兼容现有调用。
        return -1;
    }

    // ============================================================
    // Stop
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
    // Runtime directory
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
    // Copy
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
    // chmod
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
    // File exists as root
    // ============================================================

    private boolean fileExistsAsRoot(
            String path
    ) {

        if (path == null
                || path.isEmpty()) {

            return false;
        }

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "test -e "
                                    + shellQuote(
                                    path
                            )
                    )
                            .redirectErrorStream(true)
                            .start();

            int code =
                    p.waitFor();

            return code == 0;

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // Inspect ELF from Java file
    // ============================================================

    private ElfInfo inspectElfFile(
            File file
    ) {

        ElfInfo info =
                new ElfInfo();

        if (file == null
                || !file.exists()
                || !file.isFile()) {

            return info;
        }

        FileInputStream fis = null;

        try {

            fis =
                    new FileInputStream(
                            file
                    );

            byte[] ident =
                    new byte[16];

            int n =
                    fis.read(
                            ident
                    );

            if (n < 4) {

                return info;
            }

            if ((ident[0] & 0xff) == 0x7f
                    && (ident[1] & 0xff) == 0x45
                    && (ident[2] & 0xff) == 0x4c
                    && (ident[3] & 0xff) == 0x46) {

                info.isElf = true;

                int elfClass =
                        ident[4] & 0xff;

                int endian =
                        ident[5] & 0xff;

                info.littleEndian =
                        endian == 1;

                if (elfClass == 1) {

                    info.elfClass =
                            "ELF32";

                } else if (elfClass == 2) {

                    info.elfClass =
                            "ELF64";

                } else {

                    info.elfClass =
                            "UNKNOWN("
                                    + elfClass
                                    + ")";
                }

                int osabi =
                        ident[7] & 0xff;

                info.osAbi =
                        elfOsAbi(
                                osabi
                        );

                byte[] header;

                if (elfClass == 1) {

                    header =
                            new byte[52];

                } else if (elfClass == 2) {

                    header =
                            new byte[64];

                } else {

                    return info;
                }

                System.arraycopy(
                        ident,
                        0,
                        header,
                        0,
                        Math.min(
                                ident.length,
                                header.length
                        )
                );

                int remaining =
                        header.length
                                - ident.length;

                if (remaining > 0) {

                    int read =
                            fis.read(
                                    header,
                                    ident.length,
                                    remaining
                            );

                    if (read != remaining) {

                        return info;
                    }
                }

                long ePhOff;
                int ePhEntSize;
                int ePhNum;
                int machine;

                if (elfClass == 1) {

                    machine =
                            readU16(
                                    header,
                                    18,
                                    info.littleEndian
                            );

                    ePhOff =
                            readU32(
                                    header,
                                    28,
                                    info.littleEndian
                            );

                    ePhEntSize =
                            readU16(
                                    header,
                                    42,
                                    info.littleEndian
                            );

                    ePhNum =
                            readU16(
                                    header,
                                    44,
                                    info.littleEndian
                            );

                } else {

                    machine =
                            readU16(
                                    header,
                                    18,
                                    info.littleEndian
                            );

                    ePhOff =
                            readU64(
                                    header,
                                    32,
                                    info.littleEndian
                            );

                    ePhEntSize =
                            readU16(
                                    header,
                                    54,
                                    info.littleEndian
                            );

                    ePhNum =
                            readU16(
                                    header,
                                    56,
                                    info.littleEndian
                            );
                }

                info.machine =
                        elfMachine(
                                machine
                        );

                if (ePhOff > 0
                        && ePhNum > 0
                        && ePhNum < 4096
                        && ePhEntSize > 0) {

                    for (int i = 0;
                         i < ePhNum;
                         i++) {

                        long offset =
                                ePhOff
                                        + ((long) i
                                        * ePhEntSize);

                        if (offset
                                > file.length()) {

                            break;
                        }

                        byte[] ph =
                                new byte[
                                        ePhEntSize
                                ];

                        fis.getChannel()
                                .position(
                                        offset
                                );

                        int read =
                                fis.read(
                                        ph
                                );

                        if (read != ePhEntSize) {

                            break;
                        }

                        long pType =
                                readU32(
                                        ph,
                                        0,
                                        info.littleEndian
                                );

                        if (pType == 3) {

                            long pOffset;
                            long pFilesz;

                            if (elfClass == 1) {

                                pOffset =
                                        readU32(
                                                ph,
                                                4,
                                                info.littleEndian
                                        );

                                pFilesz =
                                        readU32(
                                                ph,
                                                16,
                                                info.littleEndian
                                        );

                            } else {

                                pOffset =
                                        readU64(
                                                ph,
                                                8,
                                                info.littleEndian
                                        );

                                pFilesz =
                                        readU64(
                                                ph,
                                                32,
                                                info.littleEndian
                                        );
                            }

                            if (pFilesz > 0
                                    && pFilesz < 4096
                                    && pOffset >= 0
                                    && pOffset < file.length()) {

                                byte[] interp =
                                        new byte[
                                                (int) pFilesz
                                        ];

                                fis.getChannel()
                                        .position(
                                                pOffset
                                        );

                                int got =
                                        fis.read(
                                                interp
                                        );

                                if (got > 0) {

                                    int end = 0;

                                    while (end < got
                                            && interp[end] != 0) {

                                        end++;
                                    }

                                    info.interpreter =
                                            new String(
                                                    interp,
                                                    0,
                                                    end,
                                                    StandardCharsets.UTF_8
                                            );
                                }
                            }

                            break;
                        }
                    }
                }

                return info;
            }

            if ((ident[0] & 0xff) == '#'
                    && (ident[1] & 0xff) == '!') {

                info.isShebang = true;

                StringBuilder sb =
                        new StringBuilder();

                sb.append(
                        (char) ident[2]
                );

                sb.append(
                        (char) ident[3]
                );

                for (int i = 4;
                     i < ident.length;
                     i++) {

                    int c =
                            ident[i] & 0xff;

                    if (c == 0
                            || c == '\n'
                            || c == '\r') {

                        break;
                    }

                    sb.append(
                            (char) c
                    );
                }

                info.shebang =
                        sb.toString().trim();

                return info;
            }

        } catch (Exception ignored) {

        } finally {

            try {

                if (fis != null) {

                    fis.close();
                }

            } catch (Exception ignored) {
            }
        }

        return info;
    }

    // ============================================================
    // Root inspect
    // ============================================================

    private ElfInfo inspectElfAsRoot(
            String path
    ) {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "cat "
                                    + shellQuote(
                                    path
                            )
                    )
                            .redirectErrorStream(true)
                            .start();

            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream();

            InputStream input =
                    p.getInputStream();

            byte[] buffer =
                    new byte[8192];

            int total = 0;
            int read;

            while ((read =
                    input.read(buffer)) != -1) {

                if (read <= 0) {

                    continue;
                }

                int allowed =
                        Math.min(
                                read,
                                1024 * 1024 - total
                        );

                if (allowed > 0) {

                    bos.write(
                            buffer,
                            0,
                            allowed
                    );

                    total += allowed;
                }

                if (total >= 1024 * 1024) {

                    break;
                }
            }

            p.waitFor();

            byte[] data =
                    bos.toByteArray();

            return inspectElfBytes(
                    data
            );

        } catch (Exception e) {

            return new ElfInfo();
        }
    }

    // ============================================================
    // Inspect byte array
    // ============================================================

    private ElfInfo inspectElfBytes(
            byte[] data
    ) {

        ElfInfo info =
                new ElfInfo();

        if (data == null
                || data.length < 4) {

            return info;
        }

        if ((data[0] & 0xff) == 0x7f
                && (data[1] & 0xff) == 0x45
                && (data[2] & 0xff) == 0x4c
                && (data[3] & 0xff) == 0x46) {

            info.isElf = true;

            if (data.length < 16) {

                return info;
            }

            int cls =
                    data[4] & 0xff;

            int endian =
                    data[5] & 0xff;

            info.littleEndian =
                    endian == 1;

            if (cls == 1) {

                info.elfClass =
                        "ELF32";

            } else if (cls == 2) {

                info.elfClass =
                        "ELF64";

            } else {

                info.elfClass =
                        "UNKNOWN";
            }

            info.osAbi =
                    elfOsAbi(
                            data[7] & 0xff
                    );

            if (cls == 1
                    && data.length >= 52) {

                int machine =
                        readU16(
                                data,
                                18,
                                info.littleEndian
                        );

                info.machine =
                        elfMachine(
                                machine
                        );

                long phoff =
                        readU32(
                                data,
                                28,
                                info.littleEndian
                        );

                int phentsize =
                        readU16(
                                data,
                                42,
                                info.littleEndian
                        );

                int phnum =
                        readU16(
                                data,
                                44,
                                info.littleEndian
                        );

                parseInterpreterFromBytes(
                        data,
                        true,
                        phoff,
                        phentsize,
                        phnum,
                        info
                );

            } else if (cls == 2
                    && data.length >= 64) {

                int machine =
                        readU16(
                                data,
                                18,
                                info.littleEndian
                        );

                info.machine =
                        elfMachine(
                                machine
                        );

                long phoff =
                        readU64(
                                data,
                                32,
                                info.littleEndian
                        );

                int phentsize =
                        readU16(
                                data,
                                54,
                                info.littleEndian
                        );

                int phnum =
                        readU16(
                                data,
                                56,
                                info.littleEndian
                        );

                parseInterpreterFromBytes(
                        data,
                        false,
                        phoff,
                        phentsize,
                        phnum,
                        info
                );
            }

            return info;
        }

        if ((data[0] & 0xff) == '#'
                && (data[1] & 0xff) == '!') {

            info.isShebang = true;

            int end = 2;

            while (end < data.length) {

                int c =
                        data[end] & 0xff;

                if (c == '\n'
                        || c == '\r'
                        || c == 0) {

                    break;
                }

                end++;
            }

            info.shebang =
                    new String(
                            data,
                            2,
                            end - 2,
                            StandardCharsets.UTF_8
                    ).trim();
        }

        return info;
    }

    // ============================================================
    // Parse PT_INTERP
    // ============================================================

    private void parseInterpreterFromBytes(
            byte[] data,
            boolean elf32,
            long phoff,
            int phentsize,
            int phnum,
            ElfInfo info
    ) {

        if (phoff < 0
                || phentsize <= 0
                || phnum <= 0
                || phnum > 4096) {

            return;
        }

        for (int i = 0;
             i < phnum;
             i++) {

            long offset =
                    phoff
                            + ((long) i
                            * phentsize);

            if (offset < 0
                    || offset >= data.length
                    || offset + phentsize > data.length) {

                break;
            }

            int base =
                    (int) offset;

            long pType =
                    readU32(
                            data,
                            base,
                            info.littleEndian
                    );

            if (pType != 3) {

                continue;
            }

            long pOffset;
            long pFilesz;

            if (elf32) {

                pOffset =
                        readU32(
                                data,
                                base + 4,
                                info.littleEndian
                        );

                pFilesz =
                        readU32(
                                data,
                                base + 16,
                                info.littleEndian
                        );

            } else {

                pOffset =
                        readU64(
                                data,
                                base + 8,
                                info.littleEndian
                        );

                pFilesz =
                        readU64(
                                data,
                                base + 32,
                                info.littleEndian
                        );
            }

            if (pOffset < 0
                    || pFilesz <= 0
                    || pFilesz > 4096
                    || pOffset >= data.length) {

                return;
            }

            long end =
                    Math.min(
                            data.length,
                            pOffset + pFilesz
                    );

            if (end <= pOffset) {

                return;
            }

            int start =
                    (int) pOffset;

            int finish =
                    (int) end;

            int zero =
                    start;

            while (zero < finish
                    && data[zero] != 0) {

                zero++;
            }

            info.interpreter =
                    new String(
                            data,
                            start,
                            zero - start,
                            StandardCharsets.UTF_8
                    );

            return;
        }
    }

    // ============================================================
    // ELF uint16
    // ============================================================

    private int readU16(
            byte[] data,
            int offset,
            boolean little
    ) {

        if (offset < 0
                || offset + 2 > data.length) {

            return 0;
        }

        int b0 =
                data[offset] & 0xff;

        int b1 =
                data[offset + 1] & 0xff;

        if (little) {

            return b0
                    | (b1 << 8);

        } else {

            return (b0 << 8)
                    | b1;
        }
    }

    // ============================================================
    // ELF uint32
    // ============================================================

    private long readU32(
            byte[] data,
            int offset,
            boolean little
    ) {

        if (offset < 0
                || offset + 4 > data.length) {

            return 0;
        }

        long b0 =
                data[offset] & 0xffL;

        long b1 =
                data[offset + 1] & 0xffL;

        long b2 =
                data[offset + 2] & 0xffL;

        long b3 =
                data[offset + 3] & 0xffL;

        if (little) {

            return b0
                    | (b1 << 8)
                    | (b2 << 16)
                    | (b3 << 24);

        } else {

            return (b0 << 24)
                    | (b1 << 16)
                    | (b2 << 8)
                    | b3;
        }
    }

    // ============================================================
    // ELF uint64
    // ============================================================

    private long readU64(
            byte[] data,
            int offset,
            boolean little
    ) {

        if (offset < 0
                || offset + 8 > data.length) {

            return 0;
        }

        long result = 0;

        if (little) {

            for (int i = 7;
                 i >= 0;
                 i--) {

                result <<= 8;

                result |=
                        data[offset + i]
                                & 0xffL;
            }

        } else {

            for (int i = 0;
                 i < 8;
                 i++) {

                result <<= 8;

                result |=
                        data[offset + i]
                                & 0xffL;
            }
        }

        return result;
    }

    // ============================================================
    // ELF machine
    // ============================================================

    private String elfMachine(
            int machine
    ) {

        switch (machine) {

            case 0:
                return "NONE";

            case 3:
                return "x86";

            case 8:
                return "MIPS";

            case 20:
                return "PowerPC";

            case 21:
                return "PowerPC64";

            case 22:
                return "S390";

            case 40:
                return "ARM";

            case 43:
                return "SPARC64";

            case 62:
                return "x86_64";

            case 183:
                return "AArch64";

            case 243:
                return "RISC-V";

            case 258:
                return "LoongArch";

            default:
                return "UNKNOWN("
                        + machine
                        + ")";
        }
    }

    // ============================================================
    // ELF OS ABI
    // ============================================================

    private String elfOsAbi(
            int abi
    ) {

        switch (abi) {

            case 0:
                return "System V";

            case 1:
                return "HP-UX";

            case 2:
                return "NetBSD";

            case 3:
                return "Linux";

            case 6:
                return "Solaris";

            case 9:
                return "FreeBSD";

            default:
                return "UNKNOWN("
                        + abi
                        + ")";
        }
    }

    // ============================================================
    // Resolve script interpreter
    // ============================================================

    private String resolveScriptInterpreter(
            String shebang
    ) {

        if (shebang == null
                || shebang.trim().isEmpty()) {

            return "/system/bin/sh";
        }

        String value =
                shebang.trim();

        String[] parts =
                value.split(
                        "\\s+"
                );

        String interpreter =
                parts[0];

        if (fileExistsAsRoot(
                interpreter
        )) {

            return interpreter;
        }

        if ("/usr/bin/env".equals(
                interpreter
        )
                || "/bin/env".equals(
                interpreter
        )) {

            if (parts.length >= 2) {

                String name =
                        parts[1];

                String[] paths = {

                        "/system/bin/"
                                + name,

                        "/system/xbin/"
                                + name,

                        "/data/local/tmp/"
                                + name,

                        RUNTIME_DIR
                                + "/"
                                + name
                };

                for (String path :
                        paths) {

                    if (fileExistsAsRoot(
                            path
                    )) {

                        return path;
                    }
                }
            }

            return "/system/bin/sh";
        }

        if ("/bin/sh".equals(
                interpreter
        )
                || "/usr/bin/sh".equals(
                interpreter
        )
                || "/system/bin/sh".equals(
                interpreter
        )) {

            return "/system/bin/sh";
        }

        if ("/bin/bash".equals(
                interpreter
        )
                || "/usr/bin/bash".equals(
                interpreter
        )) {

            String[] bashPaths = {

                    "/system/bin/bash",

                    "/system/xbin/bash",

                    RUNTIME_DIR + "/bash"
            };

            for (String path :
                    bashPaths) {

                if (fileExistsAsRoot(
                        path
                )) {

                    return path;
                }
            }
        }

        return interpreter;
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
    // Clean output
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
    // File name
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
    // Sanitize filename
    // ============================================================

    private String sanitizeFileName(
            String name
    ) {

        if (name == null
                || name.isEmpty()) {

            return "file_"
                    + System.currentTimeMillis();
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
                    "file_"
                            + System.currentTimeMillis();
        }

        return name;
    }

    // ============================================================
    // Normalize path
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

        if (savedPath.startsWith(
                RUNTIME_DIR + "/"
        )) {

            return savedPath;
        }

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
    // Save
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
    // Script adapter
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
    // Output
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
    // Exception
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
    // ELF info
    // ============================================================

    private static class ElfInfo {

        boolean isElf = false;

        boolean isShebang = false;

        boolean littleEndian = true;

        String elfClass =
                "UNKNOWN";

        String machine =
                "UNKNOWN";

        String osAbi =
                "UNKNOWN";

        String interpreter =
                null;

        String shebang =
                null;
    }

    // ============================================================
    // Destroy
    // ============================================================

    @Override
    protected void onDestroy() {

        stopCurrentElf();

        super.onDestroy();
    }
                    }
