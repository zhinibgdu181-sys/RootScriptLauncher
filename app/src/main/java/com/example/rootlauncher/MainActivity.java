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

    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;

    private final ArrayList<String> scriptList = new ArrayList<>();
    private ScriptAdapter adapter;

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean elfRunning = false;

    private volatile String pendingScriptPath = null;

    private android.content.SharedPreferences prefs;

    private File busyboxFile;

    private boolean keyboardVisible = false;

    private static final int SCRIPT_LIST_KEYBOARD_DP = 120;

    private static final String BUILTIN_KAIROS =
            "Kairos_Driver_Loader_Release_90f76e9.sh";

    private static final String BUILTIN_TIME =
            "TIME_Cloud_Loader_Release_1732727.sh";

    private static final String BUSYBOX_ASSET =
            "busybox";

    private static final String RUNTIME_DIR =
            "/data/local/tmp/com.example.rootlauncher/files";

    private static final String RUNTIME_BUSYBOX =
            RUNTIME_DIR + "/busybox";

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

                        Uri uri = result.getData().getData();

                        if (uri == null) {
                            return;
                        }

                        String displayName = getFileName(uri);

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
                                        new FileOutputStream(tempFile);

                                byte[] buffer = new byte[8192];

                                int len;

                                while ((len = is.read(buffer)) > 0) {

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

                                synchronized (scriptList) {

                                    if (!scriptList.contains(
                                            runtimePath
                                    )) {

                                        scriptList.add(runtimePath);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        setContentView(R.layout.activity_main);

        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);

        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnSend = findViewById(R.id.btnSend);

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

            for (String savedPath : savedScripts) {

                String normalized =
                        normalizeSavedPath(savedPath);

                if (normalized != null
                        && !scriptList.contains(normalized)) {

                    scriptList.add(normalized);
                }
            }
        }

        addBuiltinScript(BUILTIN_KAIROS);
        addBuiltinScript(BUILTIN_TIME);

        adapter = new ScriptAdapter();

        lvScripts.setAdapter(adapter);

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

            installBuiltinAsset(BUILTIN_KAIROS);
            installBuiltinAsset(BUILTIN_TIME);

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

            filePickerLauncher.launch(intent);
        });

        btnSend.setOnClickListener(v -> {

            String input =
                    etInput.getText().toString();

            if (input.trim().isEmpty()) {

                return;
            }

            if (elfRunning
                    && process != null
                    && writer != null) {

                sendInputToElf(input);

            } else {

                executeCommand(input);
            }
        });
    }

    private void setupKeyboardListener() {

        final View rootView =
                findViewById(android.R.id.content);

        rootView.getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> {

                    if (lvScripts == null) {

                        return;
                    }

                    Rect visibleRect = new Rect();

                    rootView.getWindowVisibleDisplayFrame(
                            visibleRect
                    );

                    int rootHeight =
                            rootView.getRootView().getHeight();

                    int visibleHeight =
                            visibleRect.bottom
                                    - visibleRect.top;

                    int keyboardHeight =
                            rootHeight
                                    - visibleHeight;

                    boolean nowVisible =
                            keyboardHeight
                                    > rootHeight * 0.15f;

                    if (nowVisible == keyboardVisible) {

                        return;
                    }

                    keyboardVisible = nowVisible;

                    setScriptListKeyboardMode(
                            keyboardVisible
                    );
                });
    }

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

            params.height = 0;

            params.matchConstraintPercentHeight =
                    0.55f;
        }

        lvScripts.setLayoutParams(params);

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

    private int dpToPx(int dp) {

        return (int) (
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density
                        + 0.5f
        );
    }

    private void addBuiltinScript(
            String assetName
    ) {

        String runtimePath =
                RUNTIME_DIR
                        + "/"
                        + assetName;

        synchronized (scriptList) {

            if (!scriptList.contains(runtimePath)) {

                scriptList.add(runtimePath);
            }
        }

        saveScripts();
    }

    private boolean installBuiltinAsset(
            String assetName
    ) {

        File tempFile =
                new File(
                        getFilesDir(),
                        "builtin_" + assetName
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
                    getAssets().open(assetName);

            FileOutputStream fos =
                    new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];

            int len;

            while ((len = is.read(buffer)) > 0) {

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

            String runtimePath =
                    RUNTIME_DIR
                            + "/"
                            + assetName;

            if (!copyFileAsRoot(
                    tempFile.getAbsolutePath(),
                    runtimePath
            )) {

                appendText(
                        "[内置文件] Root 复制失败："
                                + assetName
                                + "\n"
                );

                return false;
            }

            if (!chmod755(runtimePath)) {

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

    private void sendInputToElf(
            String input
    ) {

        try {

            BufferedWriter currentWriter = writer;
            Process currentProcess = process;

            if (currentWriter == null
                    || currentProcess == null
                    || !elfRunning) {

                appendText(
                        "[输入通道尚未建立]\n"
                );

                return;
            }

            currentWriter.write(input);
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

    private void executeCommand(
            String cmd
    ) {

        if (cmd == null
                || cmd.trim().isEmpty()) {

            return;
        }

        final String command = cmd.trim();

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
                        buildEnvironmentCommand(command);

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

                pb.redirectErrorStream(true);

                Process p = pb.start();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        p.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                char[] buffer = new char[1024];

                int count;

                while ((count =
                        reader.read(buffer)) != -1) {

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
                            cleanElfOutput(raw);

                    if (!clean.isEmpty()) {

                        appendText(clean);
                    }
                }

                int exitCode = p.waitFor();

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
                        + shellQuote(RUNTIME_DIR)
                        + "; "
                        + "export TMPDIR="
                        + shellQuote(RUNTIME_DIR)
                        + "; "
                        + command;
    }

    private String findSu() {

        String[] suPaths = {

                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su"
        };

        for (String path : suPaths) {

            if (new File(path).exists()) {

                return path;
            }
        }

        return "su";
    }

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
                    && output.contains("uid=0");

        } catch (Exception e) {

            return false;
        }
    }

    private void showRootDialog() {

        runOnUiThread(() -> {

            if (isFinishing()
                    || isDestroyed()) {

                return;
            }

            new AlertDialog.Builder(
                    MainActivity.this
            )
                    .setTitle("需要 Root 权限")
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

                                        pendingScriptPath = null;

                                        if (path != null) {

                                            runElfReal(path);
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

    private void runElf(
            String scriptPath
    ) {

        new Thread(() -> {

            if (!checkRoot()) {

                pendingScriptPath = scriptPath;

                appendText(
                        "[ELF] 没有 Root，等待授权\n"
                );

                showRootDialog();

                return;
            }

            runElfReal(scriptPath);

        }).start();
    }

    private void runElfReal(
            String scriptPath
    ) {

        stopCurrentElf();

        try {

            if (!checkRoot()) {

                pendingScriptPath = scriptPath;

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
                    normalizeSavedPath(scriptPath);

            if (runtimePath == null) {

                appendText(
                        "[ELF] 无效路径\n"
                );

                return;
            }

            File elf =
                    new File(runtimePath);

            String fileName =
                    elf.getName();

            if (BUILTIN_KAIROS.equals(fileName)
                    || BUILTIN_TIME.equals(fileName)) {

                if (!elf.exists()
                        || elf.length() == 0) {

                    appendText(
                            "[ELF] 内置文件不存在，重新安装："
                                    + fileName
                                    + "\n"
                    );

                    if (!installBuiltinAsset(fileName)) {

                        appendText(
                                "[ELF] 内置文件安装失败\n"
                        );

                        return;
                    }
                }
            }

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

            if (!chmod755(runtimePath)) {

                appendText(
                        "[ELF] chmod 755 失败\n"
                );

                return;
            }

            String lsOutput =
                    rootLs(runtimePath);

            if (lsOutput != null) {

                appendText(
                        "[ELF] 文件权限："
                                + lsOutput.trim()
                                + "\n"
                );
            }

            String magic =
                    readFileMagicAsRoot(runtimePath);

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

            /*
             * 保留 BusyBox 初始化功能。
             *
             * 但执行 ELF 时不再使用：
             *
             * busybox script -q -c ...
             *
             * 而是直接 exec ELF。
             */
            extractAndPrepareBusybox();

            String elfDir =
                    elf.getParent();

            if (elfDir == null) {

                elfDir = RUNTIME_DIR;
            }

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
                            + shellQuote(RUNTIME_DIR)
                            + "; "
                            + "export TMPDIR="
                            + shellQuote(RUNTIME_DIR)
                            + "; "
                            + "export LD_LIBRARY_PATH="
                            + shellQuote(
                                    "/system/lib64:/vendor/lib64"
                            )
                            + ":$LD_LIBRARY_PATH; "
                            + "cd "
                            + shellQuote(elfDir)
                            + " || exit 126; ";

            String elfCommand =
                    "exec "
                            + shellQuote(
                                    elf.getAbsolutePath()
                            );

            String command =
                    env
                            + elfCommand;

            appendText(
                    "[ELF] linker：/system/bin/linker64\n"
            );

            appendText(
                    "[ELF] 执行方式：直接 exec\n"
            );

            appendText(
                    "[ELF] Shell command：\n"
                            + command
                            + "\n"
            );

            ProcessBuilder pb =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    );

            pb.redirectErrorStream(false);

            try {

                pb.directory(
                        new File(elfDir)
                );

            } catch (Exception ignored) {
            }

            process = pb.start();

            final Process currentProcess =
                    process;

            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    currentProcess.getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            elfRunning = true;

            appendText(
                    "[+] ELF Process 已启动\n"
            );

            /*
             * 不再调用 Process.pid()
             *
             * Android 的 Process API / 当前项目编译环境
             * 不保证提供 pid()。
             */
            appendText(
                    "[+] PID：不可用（不影响执行）\n"
            );

            appendText(
                    "================================\n\n"
            );

            Thread stdoutThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess.getInputStream(),
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
                                        cleanElfOutput(raw);

                                if (!clean.isEmpty()) {

                                    appendText(clean);
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

            stdoutThread.setName("ELF-stdout");

            Thread stderrThread =
                    new Thread(() -> {

                        try {

                            InputStreamReader reader =
                                    new InputStreamReader(
                                            currentProcess.getErrorStream(),
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
                                        cleanElfOutput(raw);

                                if (!clean.isEmpty()) {

                                    appendText(clean);
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

            stderrThread.setName("ELF-stderr");

            stdoutThread.start();
            stderrThread.start();

            new Thread(() -> {

                try {

                    int exitCode =
                            currentProcess.waitFor();

                    try {

                        stdoutThread.join(1500);

                    } catch (Exception ignored) {
                    }

                    try {

                        stderrThread.join(1500);

                    } catch (Exception ignored) {
                    }

                    appendText(
                            "\n[ELF exit "
                                    + exitCode
                                    + "]\n"
                    );

                } catch (Exception e) {

                    appendText(
                            "\n[ELF wait 失败] "
                                    + safeMessage(e)
                                    + "\n"
                    );

                } finally {

                    if (process == currentProcess) {

                        writer = null;
                        process = null;
                        elfRunning = false;
                    }
                }

            }, "ELF-waiter").start();

        } catch (Exception e) {

            writer = null;
            process = null;
            elfRunning = false;

            appendText(
                    "\n[ELF 启动失败]\n"
                            + safeMessage(e)
                            + "\n"
            );
        }
    }

    /*
     * ============================================================
     * PID
     * ============================================================
     *
     * 这里故意不调用 Process.pid()。
     *
     * 原来的：
     *
     *     return p.pid();
     *
     * 会导致你的 assembleRelease 编译失败。
     *
     * 返回 -1 表示当前环境不提供 PID。
     * 这不会影响 Process 的启动、输入、输出和终止。
     */
    private long getProcessPid(
            Process p
    ) {

        return -1L;
    }

    private void stopCurrentElf() {

        elfRunning = false;

        try {

            BufferedWriter currentWriter = writer;

            if (currentWriter != null) {

                currentWriter.close();
            }

        } catch (Exception ignored) {
        }

        writer = null;

        Process currentProcess = process;

        if (currentProcess != null) {

            try {

                /*
                 * 不使用 Process.isAlive()。
                 *
                 * 通过 exitValue() 判断进程是否已经结束。
                 */
                try {

                    currentProcess.exitValue();

                } catch (IllegalThreadStateException stillRunning) {

                    currentProcess.destroy();

                    /*
                     * 给 destroy 一点时间。
                     */
                    try {

                        Thread.sleep(100);

                    } catch (InterruptedException interrupted) {

                        Thread.currentThread().interrupt();
                    }

                    try {

                        currentProcess.exitValue();

                    } catch (IllegalThreadStateException ignored) {

                        /*
                         * 如果仍然没有结束，
                         * 使用 destroyForcibly()。
                         */
                        try {

                            currentProcess.destroyForcibly();

                        } catch (Throwable ignored2) {
                        }
                    }
                }

            } catch (Throwable ignored) {
            }
        }

        process = null;
    }

    private boolean prepareRuntimeDir() {

        try {

            String command =
                    "mkdir -p "
                            + shellQuote(RUNTIME_DIR)
                            + " && chmod 755 "
                            + shellQuote(RUNTIME_DIR);

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

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

    private boolean copyFileAsRoot(
            String source,
            String destination
    ) {

        try {

            String command =
                    "mkdir -p "
                            + shellQuote(RUNTIME_DIR)
                            + "; "
                            + "cat "
                            + shellQuote(source)
                            + " > "
                            + shellQuote(destination)
                            + "; "
                            + "chmod 755 "
                            + shellQuote(destination);

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

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
                    new File(destination);

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

    private boolean chmod755(
            String path
    ) {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "chmod 755 "
                                    + shellQuote(path)
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

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

    private String rootLs(
            String path
    ) {

        try {

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            "ls -l "
                                    + shellQuote(path)
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

            p.waitFor();

            return output;

        } catch (Exception e) {

            return null;
        }
    }

    private String readFileMagicAsRoot(
            String path
    ) {

        try {

            String command =
                    "od -An -tx1 -N 4 "
                            + shellQuote(path);

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

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

    private boolean extractAndPrepareBusybox() {

        File tempFile =
                new File(
                        getFilesDir(),
                        "busybox_temp"
                );

        try {

            InputStream is =
                    getAssets().open(BUSYBOX_ASSET);

            FileOutputStream fos =
                    new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];

            int len;

            while ((len = is.read(buffer)) > 0) {

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
                            + shellQuote(destination)
                            + "; chmod 755 "
                            + shellQuote(destination);

            Process p =
                    new ProcessBuilder(
                            findSu(),
                            "-c",
                            command
                    )
                            .redirectErrorStream(true)
                            .start();

            String output =
                    readAll(p.getInputStream());

            int exit = p.waitFor();

            if (exit != 0) {

                appendText(
                        "[BusyBox] 安装失败\n"
                                + output
                );

                return false;
            }

            busyboxFile =
                    new File(RUNTIME_BUSYBOX);

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

    private boolean hasBusyboxApplet(
            String appletList,
            String wanted
    ) {

        if (appletList == null
                || wanted == null) {

            return false;
        }

        String[] applets =
                appletList.split("\\s+");

        for (String applet : applets) {

            if (wanted.equals(applet.trim())) {

                return true;
            }
        }

        return false;
    }

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

            char[] buffer = new char[1024];

            int count;

            while ((count =
                    reader.read(buffer)) != -1) {

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

    private String getFileName(
            Uri uri
    ) {

        String result = null;

        if ("content".equals(uri.getScheme())) {

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
                                    OpenableColumns.DISPLAY_NAME
                            );

                    if (nameIndex != -1) {

                        result =
                                cursor.getString(nameIndex);
                    }
                }

            } catch (Exception ignored) {
            }
        }

        if (result == null) {

            result = uri.getPath();

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
                name.replace("/", "_");

        name =
                name.replace("\\", "_");

        name =
                name.replace("\u0000", "_");

        if (".".equals(name)
                || "..".equals(name)) {

            name =
                    "script_"
                            + System.currentTimeMillis()
                            + ".sh";
        }

        return name;
    }

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
                new File(savedPath).getName();

        if (fileName == null
                || fileName.isEmpty()) {

            return null;
        }

        return RUNTIME_DIR
                + "/"
                + fileName;
    }

    private void saveScripts() {

        if (prefs == null) {

            return;
        }

        synchronized (scriptList) {

            prefs.edit()
                    .putStringSet(
                            "scripts",
                            new HashSet<>(scriptList)
                    )
                    .apply();
        }
    }

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
                                .from(getContext())
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
                        scriptList.get(position);
            }

            String fileName =
                    new File(path).getName();

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

                tvName.setText(fileName);
            }

            if (btnRun != null) {

                btnRun.setOnClickListener(
                        v -> runElf(path)
                );
            }

            if (btnDelete != null) {

                btnDelete.setOnClickListener(
                        v -> {

                            String deletePath = null;

                            synchronized (scriptList) {

                                if (position >= 0
                                        && position < scriptList.size()) {

                                    deletePath =
                                            scriptList.remove(position);
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
                                                                    + shellQuote(target)
                                                    )
                                                            .redirectErrorStream(true)
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

            tvOutput.append(text);

            if (scrollView != null) {

                scrollView.post(() ->
                        scrollView.fullScroll(
                                View.FOCUS_DOWN
                        )
                );
            }
        });
    }

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

    @Override
    protected void onDestroy() {

        stopCurrentElf();

        super.onDestroy();
    }
}


