package com.example.rootlauncher;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    // ============================================================
    // 基本配置
    // ============================================================

    private static final String TAG = "RootLauncher";

    private static final String RUNTIME_DIR =
            "/data/local/tmp/com.example.rootlauncher/files";

    private static final String PREFS_NAME =
            "root_launcher_prefs";

    private static final String PREF_SCRIPT_LIST =
            "script_list";

    // 你的两个内置文件
    private static final String BUILTIN_KAIROS =
            "Kairos_Driver_Loader_Release_90f76e9.sh";

    private static final String BUILTIN_TIME =
            "TIME_Cloud_Loader_Release_1732727.sh";

    private static final String[] BUILTIN_ASSETS = {
            BUILTIN_KAIROS,
            BUILTIN_TIME
    };

    // ============================================================
    // UI
    // ============================================================

    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;

    private Button btnAdd;
    private Button btnRun;
    private Button btnStop;
    private Button btnClear;

    // ============================================================
    // 数据
    // ============================================================

    private final ArrayList<String> scriptList = new ArrayList<>();

    private ArrayAdapter<String> scriptAdapter;

    private android.content.SharedPreferences preferences;

    // ============================================================
    // 线程
    // ============================================================

    private final ExecutorService executor =
            Executors.newCachedThreadPool();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    // ============================================================
    // Root / 当前进程
    // ============================================================

    private String suPath = null;

    private volatile Process currentProcess = null;

    private final Object processLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);

    // 防止两个点击同时进入启动流程
    private final Object launchLock = new Object();

    // ============================================================
    // Activity Result
    // ============================================================

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) {
                            appendText("[ADD] 用户取消文件选择\n");
                            return;
                        }

                        executor.execute(() -> importSelectedFile(uri));
                    }
            );

    // ============================================================
    // onCreate
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        initViews();

        preferences = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
        );

        loadScriptList();

        setupListView();
        setupButtons();
        setupInput();

        appendText("Root Launcher\n");
        appendText("==============================\n");

        appendText("[INIT] Runtime:\n");
        appendText(RUNTIME_DIR + "\n");

        appendText("[INIT] 等待 Root...\n");

        executor.execute(() -> {
            suPath = findSu();

            if (suPath == null) {
                appendText("[ERROR] 找不到 su\n");
                appendText("[ERROR] 已检查：\n");
                appendText("  /system/bin/su\n");
                appendText("  /system/xbin/su\n");
                appendText("  /sbin/su\n");
                appendText("  /debug_ramdisk/su\n");
                return;
            }

            appendText("[+] Root shell: " + suPath + "\n");

            if (!checkRoot()) {
                appendText("[ERROR] su 存在，但没有获得 uid=0\n");
                return;
            }

            appendText("[+] Root UID=0\n");

            if (!prepareRuntimeDir()) {
                appendText("[ERROR] Runtime 目录创建失败\n");
                return;
            }

            appendText("[+] Runtime 目录已准备\n");

            installBuiltinAssets();

            appendText("[INIT] 初始化完成\n");
            appendText("[INIT] 可以选择脚本运行\n");
        });
    }

    // ============================================================
    // UI 初始化
    // ============================================================

    private void initViews() {
        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);

        // 如果你的 XML 有这些按钮，就自动使用。
        // 没有的话保持 null，不影响核心功能。
        btnAdd = findViewByIdSafe(R.id.btnAdd);
        btnRun = findViewByIdSafe(R.id.btnRun);
        btnStop = findViewByIdSafe(R.id.btnStop);
        btnClear = findViewByIdSafe(R.id.btnClear);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T findViewByIdSafe(int id) {
        try {
            return findViewById(id);
        } catch (Exception e) {
            return null;
        }
    }

    private void setupListView() {
        scriptAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_activated_1,
                scriptList
        );

        lvScripts.setAdapter(scriptAdapter);

        lvScripts.setOnItemClickListener(
                (parent, view, position, id) -> {

                    if (position < 0 || position >= scriptList.size()) {
                        return;
                    }

                    String path = scriptList.get(position);

                    etInput.setText(path);

                    appendText("\n");
                    appendText("[SELECT] " + path + "\n");
                }
        );

        lvScripts.setOnItemLongClickListener(
                (parent, view, position, id) -> {

                    if (position < 0 || position >= scriptList.size()) {
                        return true;
                    }

                    String path = scriptList.get(position);

                    new AlertDialog.Builder(this)
                            .setTitle("删除脚本")
                            .setMessage(path)
                            .setPositiveButton("删除", (dialog, which) -> {
                                removeScript(position);
                            })
                            .setNegativeButton("取消", null)
                            .show();

                    return true;
                }
        );
    }

    private void setupButtons() {

        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> openFilePicker());
        }

        if (btnRun != null) {
            btnRun.setOnClickListener(v -> {
                String path = etInput.getText()
                        .toString()
                        .trim();

                if (path.isEmpty()) {
                    appendText("[ERROR] 没有选择文件\n");
                    return;
                }

                runFile(path);
            });
        }

        if (btnStop != null) {
            btnStop.setOnClickListener(v -> stopCurrentProcess());
        }

        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                tvOutput.setText("");
            });
        }
    }

    private void setupInput() {

        if (etInput == null) {
            return;
        }

        etInput.setSingleLine(true);

        etInput.setOnEditorActionListener(
                (v, actionId, event) -> {

                    boolean enter =
                            actionId == EditorInfo.IME_ACTION_GO ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_RUN;

                    if (event != null &&
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                            event.getAction() == KeyEvent.ACTION_DOWN) {
                        enter = true;
                    }

                    if (enter) {
                        String path = etInput.getText()
                                .toString()
                                .trim();

                        if (!path.isEmpty()) {
                            runFile(path);
                        }

                        return true;
                    }

                    return false;
                }
        );
    }

    // ============================================================
    // 文件选择
    // ============================================================

    private void openFilePicker() {

        filePicker.launch(new String[]{
                "*/*"
        });
    }

    // ============================================================
    // 导入文件
    // ============================================================

    private void importSelectedFile(Uri uri) {

        String originalName = getDisplayName(uri);

        if (originalName == null || originalName.trim().isEmpty()) {
            originalName = "imported_file";
        }

        String fileName = sanitizeFileName(originalName);

        if (fileName.isEmpty()) {
            fileName = "imported_file";
        }

        File localFile = new File(
                getFilesDir(),
                fileName
        );

        appendText("\n");
        appendText("[ADD] 文件："
                + fileName
                + "\n");

        try {

            try (InputStream in =
                         getContentResolver().openInputStream(uri);

                 FileOutputStream out =
                         new FileOutputStream(localFile)) {

                if (in == null) {
                    throw new IOException(
                            "无法打开 URI"
                    );
                }

                byte[] buffer = new byte[64 * 1024];

                int n;

                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }

                out.flush();
            }

            long size = localFile.length();

            appendText("[ADD] 本地缓存："
                    + localFile.getAbsolutePath()
                    + "\n");

            appendText("[ADD] 大小："
                    + size
                    + " bytes\n");

            if (size <= 0) {
                appendText("[ERROR] 文件为空\n");
                return;
            }

            String runtimePath =
                    RUNTIME_DIR + "/" + fileName;

            appendText("[ADD] Root 安装到：\n");
            appendText(runtimePath + "\n");

            if (!copyFileAsRoot(
                    localFile,
                    runtimePath
            )) {
                appendText(
                        "[ERROR] Root 复制失败\n"
                );
                return;
            }

            addScript(runtimePath);

            appendText("[+] 导入完成\n");

            inspectRuntimeFile(runtimePath);

        } catch (Exception e) {

            appendText(
                    "[ERROR] 导入异常："
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage()
                            + "\n"
            );
        } finally {

            try {
                if (localFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    localFile.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // 内置文件
    // ============================================================

    private void installBuiltinAssets() {

        for (String assetName : BUILTIN_ASSETS) {

            appendText("\n");
            appendText(
                    "[内置文件] "
                            + assetName
                            + "\n"
            );

            try {

                String runtimePath =
                        RUNTIME_DIR + "/" + assetName;

                byte[] assetBytes =
                        readAsset(assetName);

                if (assetBytes == null ||
                        assetBytes.length == 0) {

                    appendText(
                            "[ERROR] Asset 为空\n"
                    );

                    continue;
                }

                appendText(
                        "[内置文件] 大小："
                                + assetBytes.length
                                + " bytes\n"
                );

                String elfInfo =
                        inspectElfBytes(assetBytes);

                if (elfInfo != null) {
                    appendText(
                            "[内置 ELF] "
                                    + elfInfo
                                    + "\n"
                    );
                } else if (isShebang(assetBytes)) {
                    appendText(
                            "[内置文件] 检测到 shebang 脚本\n"
                    );
                } else {
                    appendText(
                            "[内置文件] 未检测到 ELF/shebang\n"
                    );
                }

                File tempFile =
                        new File(
                                getFilesDir(),
                                ".builtin_" + assetName
                        );

                try (FileOutputStream out =
                             new FileOutputStream(tempFile)) {

                    out.write(assetBytes);
                    out.flush();
                }

                if (!copyFileAsRoot(
                        tempFile,
                        runtimePath
                )) {

                    appendText(
                            "[ERROR] 内置文件安装失败："
                                    + runtimePath
                                    + "\n"
                    );

                    continue;
                }

                addScript(runtimePath);

                appendText(
                        "[+] 内置文件安装完成："
                                + runtimePath
                                + "\n"
                );

                inspectRuntimeFile(runtimePath);

                // 删除 App 自己的临时副本
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();

            } catch (Exception e) {

                appendText(
                        "[ERROR] 内置文件安装异常："
                                + e.getClass().getSimpleName()
                                + ": "
                                + e.getMessage()
                                + "\n"
                );
            }
        }

        refreshScriptList();
    }

    private byte[] readAsset(String assetName)
            throws IOException {

        try (InputStream in =
                     getAssets().open(assetName);

             ByteArrayOutputStream out =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[64 * 1024];

            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toByteArray();
        }
    }

    // ============================================================
    // Runtime 目录
    // ============================================================

    private boolean prepareRuntimeDir() {

        if (suPath == null) {
            appendText(
                    "[ERROR] suPath == null\n"
            );
            return false;
        }

        String command =
                "mkdir -p "
                        + shellQuote(RUNTIME_DIR)
                        + " && "
                        + "chmod 755 "
                        + shellQuote(RUNTIME_DIR)
                        + " && "
                        + "test -d "
                        + shellQuote(RUNTIME_DIR);

        ShellResult result =
                runRootCommand(command, 10000);

        if (!result.success) {

            appendText(
                    "[ERROR] Runtime 目录准备失败\n"
            );

            appendShellResult(result);

            return false;
        }

        return true;
    }

    // ============================================================
    // Root 检查
    // ============================================================

    private String findSu() {

        String[] candidates = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su"
        };

        for (String path : candidates) {

            try {

                File file = new File(path);

                if (file.exists()) {
                    return path;
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private boolean checkRoot() {

        if (suPath == null) {
            return false;
        }

        ShellResult result =
                runRootCommand(
                        "id",
                        10000
                );

        appendText(
                "[ROOT] id: "
                        + result.stdout.trim()
                        + "\n"
        );

        if (!result.stderr.trim().isEmpty()) {
            appendText(
                    "[ROOT] stderr: "
                            + result.stderr.trim()
                            + "\n"
            );
        }

        if (!result.success) {
            return false;
        }

        String id = result.stdout;

        return id.contains("uid=0")
                || id.trim().equals("0");
    }

    // ============================================================
    // 文件复制
    // ============================================================

    private boolean copyFileAsRoot(
            File source,
            String destination
    ) {

        if (suPath == null) {
            appendText(
                    "[COPY] su 不可用\n"
            );
            return false;
        }

        if (source == null ||
                !source.exists() ||
                source.length() <= 0) {

            appendText(
                    "[COPY] Source 不存在或为空\n"
            );

            return false;
        }

        String src =
                source.getAbsolutePath();

        String dst =
                destination;

        appendText(
                "[COPY] Source: "
                        + src
                        + "\n"
        );

        appendText(
                "[COPY] Dest: "
                        + dst
                        + "\n"
        );

        /*
         * 不使用普通 App 权限判断 destination。
         *
         * destination 是 Root 创建的，
         * 所以所有检查都在 su shell 里面进行。
         */

        String command =
                "cat "
                        + shellQuote(src)
                        + " > "
                        + shellQuote(dst)
                        + " && "
                        + "chmod 755 "
                        + shellQuote(dst)
                        + " && "
                        + "test -f "
                        + shellQuote(dst)
                        + " && "
                        + "test -s "
                        + shellQuote(dst)
                        + " && "
                        + "stat -c '%s %a %n' "
                        + shellQuote(dst);

        ShellResult result =
                runRootCommand(
                        command,
                        30000
                );

        if (!result.success) {

            appendText(
                    "[COPY] FAILED\n"
            );

            appendShellResult(result);

            return false;
        }

        appendText(
                "[COPY] OK: "
                        + result.stdout.trim()
                        + "\n"
        );

        return true;
    }

    // ============================================================
    // Runtime 文件检查
    // ============================================================

    private void inspectRuntimeFile(
            String runtimePath
    ) {

        if (suPath == null) {
            return;
        }

        appendText(
                "[FILE] 检查："
                        + runtimePath
                        + "\n"
        );

        String command =
                "echo '[FILE] stat:'; "
                        + "stat -c 'size=%s mode=%a owner=%U:%G path=%n' "
                        + shellQuote(runtimePath)
                        + "; "
                        + "echo '[FILE] ls:'; "
                        + "ls -l "
                        + shellQuote(runtimePath)
                        + "; "
                        + "echo '[FILE] test:'; "
                        + "if test -f "
                        + shellQuote(runtimePath)
                        + "; then echo file=YES; else echo file=NO; fi; "
                        + "if test -r "
                        + shellQuote(runtimePath)
                        + "; then echo readable=YES; else echo readable=NO; fi; "
                        + "if test -x "
                        + shellQuote(runtimePath)
                        + "; then echo executable=YES; else echo executable=NO; fi";

        ShellResult result =
                runRootCommand(
                        command,
                        10000
                );

        appendShellResult(result);
    }

    // ============================================================
    // 运行文件
    // ============================================================

    private void runFile(String path) {

        if (path == null ||
                path.trim().isEmpty()) {

            appendText(
                    "[EXEC] Path 为空\n"
            );

            return;
        }

        path = path.trim();

        final String finalPath = normalizeRuntimePath(path);

        appendText("\n");
        appendText(
                "================================\n"
        );

        appendText(
                "[EXEC] 请求执行\n"
        );

        appendText(
                "[EXEC] Path: "
                        + finalPath
                        + "\n"
        );

        executor.execute(() ->
                runFileReal(finalPath)
        );
    }

    private String normalizeRuntimePath(
            String path
    ) {

        if (path.startsWith("/")) {
            return path;
        }

        return RUNTIME_DIR + "/" +
                sanitizeFileName(path);
    }

    private void runFileReal(
            String path
    ) {

        /*
         * launchLock 只保护“启动准备阶段”，
         * 不把整个进程生命周期锁死。
         */
        synchronized (launchLock) {

            if (suPath == null) {

                appendText(
                        "[EXEC] ERROR: su 不可用\n"
                );

                return;
            }

            /*
             * 启动新的程序之前，先停止旧程序。
             */
            stopCurrentProcessInternal();

            appendText(
                    "[EXEC] Root shell: "
                            + suPath
                            + "\n"
            );

            /*
             * 所有文件检查使用 Root。
             */
            String infoCommand =
                    "if test -f "
                            + shellQuote(path)
                            + "; then "
                            + "echo FILE=YES; "
                            + "else "
                            + "echo FILE=NO; "
                            + "exit 10; "
                            + "fi; "
                            + "stat -c 'SIZE=%s MODE=%a OWNER=%U:%G' "
                            + shellQuote(path)
                            + "; "
                            + "if test -r "
                            + shellQuote(path)
                            + "; then "
                            + "echo READABLE=YES; "
                            + "else "
                            + "echo READABLE=NO; "
                            + "fi; "
                            + "if test -x "
                            + shellQuote(path)
                            + "; then "
                            + "echo EXECUTABLE=YES; "
                            + "else "
                            + "echo EXECUTABLE=NO; "
                            + "fi";

            ShellResult info =
                    runRootCommand(
                            infoCommand,
                            10000
                    );

            appendText(
                    "[EXEC] Root 文件检查：\n"
            );

            if (!info.stdout.trim().isEmpty()) {
                appendText(
                        info.stdout
                );
            }

            if (!info.stderr.trim().isEmpty()) {
                appendText(
                        "[EXEC-CHECK-ERR] "
                                + info.stderr
                );
            }

            if (!info.success) {

                appendText(
                        "[EXEC] 文件检查失败，停止执行\n"
                );

                return;
            }

            /*
             * 尝试获取文件大小。
             */
            long size =
                    parseSizeFromStat(
                            info.stdout
                    );

            appendText(
                    "[EXEC] Size: "
                            + size
                            + " bytes\n"
            );

            /*
             * 尝试从 App 本地缓存 / runtime 中分析 ELF。
             *
             * 如果普通 App 无法直接读取 Root 文件，
             * 不影响真正执行。
             */
            inspectRuntimeElfAsRoot(path);

            /*
             * 构造真正的 root shell。
             *
             * 关键点：
             *
             * 1. 不硬编码 linker64
             * 2. 不设置 LD_LIBRARY_PATH
             * 3. 不通过 sh path 参数二次解析
             * 4. cd 到工作目录
             * 5. 最终使用 exec
             *
             * ELF 如果有 PT_INTERP，
             * Android/Linux 内核会按照 ELF 自己的解释器执行。
             */
            String workDir =
                    new File(path).getParent();

            if (workDir == null ||
                    workDir.trim().isEmpty()) {
                workDir = RUNTIME_DIR;
            }

            String command =
                    "cd "
                            + shellQuote(workDir)
                            + " || exit 20; "
                            + "echo '[ROOT-EXEC] cwd='\"$PWD\"; "
                            + "echo '[ROOT-EXEC] file='"
                            + shellQuote(path)
                            + "; "
                            + "echo '[ROOT-EXEC] starting'; "
                            + "exec "
                            + shellQuote(path);

            appendText(
                    "[EXEC] COMMAND:\n"
                            + command
                            + "\n"
            );

            appendText(
                    "[EXEC] 开始执行\n"
            );

            Process process = null;

            try {

                ProcessBuilder pb =
                        new ProcessBuilder(
                                suPath,
                                "-c",
                                command
                        );

                /*
                 * stdout / stderr 分开。
                 *
                 * 这样如果 ELF loader 报错，
                 * 可以明确看到 stderr。
                 */
                pb.redirectErrorStream(false);

                process = pb.start();

                synchronized (processLock) {
                    currentProcess = process;
                    running.set(true);
                }

                final Process runningProcess =
                        process;

                Thread stdoutThread =
                        new Thread(
                                () -> readProcessStream(
                                        runningProcess.getInputStream(),
                                        false
                                ),
                                "root-stdout"
                        );

                Thread stderrThread =
                        new Thread(
                                () -> readProcessStream(
                                        runningProcess.getErrorStream(),
                                        true
                                ),
                                "root-stderr"
                        );

                stdoutThread.start();
                stderrThread.start();

                int exitCode =
                        process.waitFor();

                /*
                 * 尽量等待输出线程结束。
                 */
                try {
                    stdoutThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                try {
                    stderrThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                synchronized (processLock) {

                    if (currentProcess == process) {
                        currentProcess = null;
                    }

                    running.set(false);
                }

                appendText(
                        "[EXEC] Process exit code: "
                                + exitCode
                                + "\n"
                );

                if (exitCode == 0) {

                    appendText(
                            "[EXEC] 执行结束：SUCCESS\n"
                    );

                } else {

                    appendText(
                            "[EXEC] 执行结束：FAILED\n"
                    );

                    appendText(
                            "[EXEC] exit="
                                    + exitCode
                                    + "\n"
                    );

                    appendText(
                            "[EXEC] 如果上面 stderr 出现 "
                                    + "\"No such file\" / "
                                    + "\"Permission denied\" / "
                                    + "\"CANNOT LINK\"，"
                                    + "那就是实际失败原因。\n"
                    );
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                appendText(
                        "[EXEC] waitFor 被中断\n"
                );

            } catch (Exception e) {

                appendText(
                        "[EXEC] 启动异常："
                                + e.getClass().getName()
                                + ": "
                                + e.getMessage()
                                + "\n"
                );

            } finally {

                if (process != null) {

                    synchronized (processLock) {

                        if (currentProcess == process) {
                            currentProcess = null;
                            running.set(false);
                        }
                    }
                }
            }

            appendText(
                    "================================\n"
            );
        }
    }

    // ============================================================
    // Root ELF 检查
    // ============================================================

    private void inspectRuntimeElfAsRoot(
            String path
    ) {

        if (suPath == null) {
            return;
        }

        /*
         * 读取 ELF 前 64KB。
         *
         * 使用 dd 避免把整个 ELF 输出到 Java。
         */
        String command =
                "dd if="
                        + shellQuote(path)
                        + " bs=4096 count=16 2>/dev/null";

        try {

            ProcessBuilder pb =
                    new ProcessBuilder(
                            suPath,
                            "-c",
                            command
                    );

            Process process =
                    pb.start();

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            InputStream input =
                    process.getInputStream();

            byte[] buffer =
                    new byte[8192];

            int total = 0;

            int n;

            while ((n = input.read(buffer)) != -1) {

                int remain =
                        65536 - total;

                if (remain <= 0) {
                    break;
                }

                int write =
                        Math.min(n, remain);

                out.write(
                        buffer,
                        0,
                        write
                );

                total += write;
            }

            int exit =
                    process.waitFor();

            byte[] bytes =
                    out.toByteArray();

            if (exit != 0 ||
                    bytes.length < 4) {

                return;
            }

            String info =
                    inspectElfBytes(bytes);

            if (info != null) {

                appendText(
                        "[EXEC ELF] "
                                + info
                                + "\n"
                );
            }

        } catch (Exception e) {

            appendText(
                    "[EXEC ELF] 检查失败："
                            + e.getMessage()
                            + "\n"
            );
        }
    }

    // ============================================================
    // ELF 分析
    // ============================================================

    private String inspectElfBytes(
            byte[] data
    ) {

        if (data == null ||
                data.length < 4) {
            return null;
        }

        if ((data[0] & 0xff) != 0x7f ||
                data[1] != 'E' ||
                data[2] != 'L' ||
                data[3] != 'F') {

            return null;
        }

        if (data.length < 20) {
            return "ELF";
        }

        int elfClass =
                data[4] & 0xff;

        int dataEncoding =
                data[5] & 0xff;

        String className;

        if (elfClass == 1) {
            className = "ELF32";
        } else if (elfClass == 2) {
            className = "ELF64";
        } else {
            className =
                    "ELF class=" + elfClass;
        }

        String endian;

        if (dataEncoding == 1) {
            endian = "LE";
        } else if (dataEncoding == 2) {
            endian = "BE";
        } else {
            endian =
                    "data=" + dataEncoding;
        }

        int machine =
                readU16LE(data, 18);

        String machineName =
                elfMachineName(machine);

        int osAbi =
                data.length > 7
                        ? data[7] & 0xff
                        : -1;

        String abiName =
                elfAbiName(osAbi);

        return className
                + " / "
                + machineName
                + " / "
                + endian
                + " / ABI="
                + abiName;
    }

    private boolean isShebang(
            byte[] data
    ) {

        return data != null &&
                data.length >= 2 &&
                data[0] == '#' &&
                data[1] == '!';
    }

    private String elfMachineName(
            int machine
    ) {

        switch (machine) {

            case 3:
                return "x86";

            case 40:
                return "ARM";

            case 62:
                return "x86_64";

            case 183:
                return "AArch64";

            case 8:
                return "MIPS";

            case 20:
                return "PowerPC";

            case 21:
                return "PowerPC64";

            case 243:
                return "RISC-V";

            default:
                return "machine=" + machine;
        }
    }

    private String elfAbiName(
            int abi
    ) {

        switch (abi) {

            case 0:
                return "SYSV";

            case 3:
                return "Linux";

            case 64:
                return "ARM EABI";

            default:
                return String.valueOf(abi);
        }
    }

    private int readU16LE(
            byte[] data,
            int offset
    ) {

        if (offset < 0 ||
                offset + 1 >= data.length) {
            return 0;
        }

        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8);
    }

    // ============================================================
    // Shell
    // ============================================================

    private ShellResult runRootCommand(
            String command,
            long timeoutMs
    ) {

        if (suPath == null) {
            return new ShellResult(
                    false,
                    "",
                    "suPath == null",
                    -1
            );
        }

        Process process = null;

        try {

            ProcessBuilder pb =
                    new ProcessBuilder(
                            suPath,
                            "-c",
                            command
                    );

            pb.redirectErrorStream(false);

            process = pb.start();

            final Process p =
                    process;

            ByteArrayOutputStream stdout =
                    new ByteArrayOutputStream();

            ByteArrayOutputStream stderr =
                    new ByteArrayOutputStream();

            Thread outThread =
                    new Thread(() ->
                            copyStream(
                                    p.getInputStream(),
                                    stdout
                            )
                    );

            Thread errThread =
                    new Thread(() ->
                            copyStream(
                                    p.getErrorStream(),
                                    stderr
                            )
                    );

            outThread.start();
            errThread.start();

            long start =
                    System.currentTimeMillis();

            boolean finished = false;

            while (true) {

                try {

                    int exit =
                            process.exitValue();

                    finished = true;

                    try {
                        outThread.join(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    try {
                        errThread.join(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    return new ShellResult(
                            exit == 0,
                            stdout.toString(
                                    StandardCharsets.UTF_8.name()
                            ),
                            stderr.toString(
                                    StandardCharsets.UTF_8.name()
                            ),
                            exit
                    );

                } catch (IllegalThreadStateException ignored) {
                }

                if (System.currentTimeMillis()
                        - start > timeoutMs) {
                    break;
                }

                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!finished) {

                process.destroy();

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (process.isAlive()) {
                    process.destroyForcibly();
                }

                return new ShellResult(
                        false,
                        stdout.toString(
                                StandardCharsets.UTF_8
                                        .name()
                        ),
                        stderr.toString(
                                StandardCharsets.UTF_8
                                        .name()
                        )
                                + "\nTIMEOUT",
                        -2
                );
            }

        } catch (Exception e) {

            return new ShellResult(
                    false,
                    "",
                    e.getClass().getName()
                            + ": "
                            + e.getMessage(),
                    -1
            );
        }

        return new ShellResult(
                false,
                "",
                "unknown error",
                -1
        );
    }

    private void copyStream(
            InputStream input,
            ByteArrayOutputStream output
    ) {

        try {

            byte[] buffer =
                    new byte[8192];

            int n;

            while ((n = input.read(buffer)) != -1) {
                output.write(
                        buffer,
                        0,
                        n
                );
            }

        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // 当前程序 stdout/stderr
    // ============================================================

    private void readProcessStream(
            InputStream input,
            boolean error
    ) {

        try {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    input,
                                    StandardCharsets.UTF_8
                            )
                    );

            String line;

            while ((line = reader.readLine()) != null) {

                final String text;

                if (error) {
                    text =
                            "[STDERR] "
                                    + line
                                    + "\n";
                } else {
                    text =
                            "[STDOUT] "
                                    + line
                                    + "\n";
                }

                appendText(text);
            }

        } catch (Exception e) {

            appendText(
                    error
                            ? "[STDERR] stream error: "
                            : "[STDOUT] stream error: "
            );

            appendText(
                    e.getMessage()
                            + "\n"
            );
        }
    }

    // ============================================================
    // 停止当前进程
    // ============================================================

    private void stopCurrentProcess() {

        executor.execute(
                this::stopCurrentProcessInternal
        );
    }

    private void stopCurrentProcessInternal() {

        Process process;

        synchronized (processLock) {
            process = currentProcess;
        }

        if (process == null) {
            return;
        }

        appendText(
                "[STOP] 正在结束当前进程...\n"
        );

        try {

            process.destroy();

            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (process.isAlive()) {
                appendText(
                        "[STOP] destroy 无效，强制结束\n"
                );

                process.destroyForcibly();
            }

        } catch (Exception e) {

            appendText(
                    "[STOP] "
                            + e.getMessage()
                            + "\n"
            );

        } finally {

            synchronized (processLock) {

                if (currentProcess == process) {
                    currentProcess = null;
                    running.set(false);
                }
            }
        }
    }

    // ============================================================
    // Shell 输出
    // ============================================================

    private void appendShellResult(
            ShellResult result
    ) {

        if (result == null) {
            return;
        }

        if (!result.stdout.isEmpty()) {

            appendText(
                    result.stdout
            );

            if (!result.stdout.endsWith("\n")) {
                appendText("\n");
            }
        }

        if (!result.stderr.isEmpty()) {

            appendText(
                    "[ROOT STDERR] "
                            + result.stderr
            );

            if (!result.stderr.endsWith("\n")) {
                appendText("\n");
            }
        }

        appendText(
                "[ROOT EXIT] "
                        + result.exitCode
                        + "\n"
        );
    }

    // ============================================================
    // 脚本列表
    // ============================================================

    private void loadScriptList() {

        scriptList.clear();

        Set<String> saved =
                preferences.getStringSet(
                        PREF_SCRIPT_LIST,
                        null
                );

        if (saved != null) {

            /*
             * getStringSet 返回的集合不要直接长期持有，
             * 复制一份。
             */
            scriptList.addAll(
                    new HashSet<>(saved)
            );
        }

        refreshScriptList();
    }

    private void saveScriptList() {

        Set<String> set =
                new HashSet<>(scriptList);

        preferences.edit()
                .putStringSet(
                        PREF_SCRIPT_LIST,
                        set
                )
                .apply();
    }

    private void addScript(
            String path
    ) {

        if (path == null ||
                path.trim().isEmpty()) {
            return;
        }

        final String finalPath =
                path.trim();

        mainHandler.post(() -> {

            if (!scriptList.contains(finalPath)) {

                scriptList.add(finalPath);

                saveScriptList();

                if (scriptAdapter != null) {
                    scriptAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void removeScript(
            int position
    ) {

        if (position < 0 ||
                position >= scriptList.size()) {
            return;
        }

        scriptList.remove(position);

        saveScriptList();

        if (scriptAdapter != null) {
            scriptAdapter.notifyDataSetChanged();
        }
    }

    private void refreshScriptList() {

        mainHandler.post(() -> {

            if (scriptAdapter != null) {
                scriptAdapter.notifyDataSetChanged();
            }
        });
    }

    // ============================================================
    // 文件名
    // ============================================================

    private String getDisplayName(
            Uri uri
    ) {

        Cursor cursor = null;

        try {

            cursor =
                    getContentResolver()
                            .query(
                                    uri,
                                    new String[]{
                                            OpenableColumns.DISPLAY_NAME
                                    },
                                    null,
                                    null,
                                    null
                            );

            if (cursor != null &&
                    cursor.moveToFirst()) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );

                if (index >= 0) {
                    return cursor.getString(index);
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }

        return null;
    }

    private String sanitizeFileName(
            String name
    ) {

        if (name == null) {
            return "";
        }

        String result =
                name.trim();

        result =
                result.replace(
                        "/",
                        "_"
                );

        result =
                result.replace(
                        "\\",
                        "_"
                );

        result =
                result.replace(
                        "\u0000",
                        "_"
                );

        while (result.contains("..")) {
            result =
                    result.replace(
                            "..",
                            "_"
                    );
        }

        if (result.length() > 180) {
            result =
                    result.substring(
                            0,
                            180
                    );
        }

        return result;
    }

    // ============================================================
    // Shell 转义
    // ============================================================

    private String shellQuote(
            String value
    ) {

        if (value == null) {
            return "''";
        }

        /*
         * POSIX shell 单引号：
         *
         * abc'def
         *
         * ->
         *
         * 'abc'\''def'
         */
        return "'"
                + value.replace(
                        "'",
                        "'\\''"
                )
                + "'";
    }

    // ============================================================
    // Stat 大小解析
    // ============================================================

    private long parseSizeFromStat(
            String text
    ) {

        if (text == null) {
            return -1;
        }

        String[] lines =
                text.split("\\r?\\n");

        for (String line : lines) {

            line = line.trim();

            if (line.startsWith("SIZE=")) {

                int start =
                        "SIZE=".length();

                int end =
                        line.indexOf(
                                ' ',
                                start
                        );

                if (end < 0) {
                    end = line.length();
                }

                try {
                    return Long.parseLong(
                            line.substring(
                                    start,
                                    end
                            )
                    );
                } catch (Exception ignored) {
                }
            }
        }

        return -1;
    }

    // ============================================================
    // UI 输出
    // ============================================================

    private void appendText(
            String text
    ) {

        if (text == null) {
            return;
        }

        mainHandler.post(() -> {

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

    // ============================================================
    // Activity 生命周期
    // ============================================================

    @Override
    protected void onDestroy() {

        stopCurrentProcessInternal();

        executor.shutdownNow();

        super.onDestroy();
    }

    // ============================================================
    // ShellResult
    // ============================================================

    private static class ShellResult {

        final boolean success;
        final String stdout;
        final String stderr;
        final int exitCode;

        ShellResult(
                boolean success,
                String stdout,
                String stderr,
                int exitCode
        ) {

            this.success = success;
            this.stdout = stdout == null
                    ? ""
                    : stdout;

            this.stderr = stderr == null
                    ? ""
                    : stderr;

            this.exitCode = exitCode;
        }
    }
            }
