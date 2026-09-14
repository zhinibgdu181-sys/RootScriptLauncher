package com.example.rootlauncher;

import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.Rect;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String RUNTIME_DIR = "/data/local/tmp/com.example.rootlauncher/files";
    private static final String PREFS_NAME = "root_launcher_prefs";
    private static final String PREF_SCRIPT_LIST = "script_list";
    private static final String BUSYBOX_NAME = "busybox";

    private static final String[] BUILTIN_ASSETS = {
            "Kairos_Driver_Loader_Release_90f76e9.sh",
            "TIME_Cloud_Loader_Release_1732727.sh"
    };

    // 只保留XML里实际存在的控件
    private TextView tvOutput;
    private EditText etInput;
    private ScrollView scrollView;
    private ListView lvScripts;
    private Button btnAdd, btnSend;

    private final ArrayList<String> scriptList = new ArrayList<>();
    private ArrayAdapter<String> scriptAdapter;
    private android.content.SharedPreferences preferences;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String suPath = null;
    private volatile Process currentProcess = null;
    private OutputStream processStdin = null;
    private final AtomicBoolean isScriptRunning = new AtomicBoolean(false);
    private final Object processLock = new Object();
    private boolean isInitialized = false;

    // 文件选择器
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                executor.execute(() -> importSelectedFile(uri));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 绑定 XML 里的 6 个控件
        tvOutput = findViewById(R.id.tvOutput);
        etInput = findViewById(R.id.etInput);
        scrollView = findViewById(R.id.scrollView);
        lvScripts = findViewById(R.id.lvScripts);
        btnAdd = findViewById(R.id.btnAdd);
        btnSend = findViewById(R.id.btnSend);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadScriptList();
        setupListView();
        setupButtons();
        setupInput();

        appendText("Root Launcher 初始化中...\n");
        appendText("==============================\n");

        // 2. 初始化布局高度 (列表 45%，终端 55%)
        updateListHeight(0.45f);

        // 3. 启动 Root 初始化线程
        executor.execute(() -> {
            suPath = findSu();
            if (suPath == null) {
                appendText("[ERROR] 找不到 su，无法继续。\n");
                return;
            }
            appendText("[+] Root shell: " + suPath + "\n");

            if (!checkRoot()) {
                appendText("[ERROR] 未获取 Root 权限。\n");
                return;
            }
            appendText("[+] Root UID=0\n");

            if (!prepareRuntimeDir()) {
                appendText("[ERROR] Runtime 目录创建失败。\n");
                return;
            }

            initDnsFix();
            installBuiltinAssets();

            mainHandler.post(() -> {
                appendText("[INIT] 初始化完成，点击列表右侧 ▶ 运行脚本。\n");
                isInitialized = true;
                updateListHeight(0.45f);
            });
        });
    }

    // ====================== 键盘监听与布局控制 ======================
    private void initKeyboardListener() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!isInitialized) return;
            Rect r = new Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;

            if (keypadHeight > screenHeight * 0.15) {
                updateListHeight(0.15f); // 键盘弹起
            } else {
                updateListHeight(0.45f); // 键盘收起
            }
        });
    }

    private void updateListHeight(float percent) {
        if (lvScripts == null) return;
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) lvScripts.getLayoutParams();
        params.height = 0;
        params.matchConstraintPercentHeight = percent;
        params.matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT;
        lvScripts.setLayoutParams(params);
        lvScripts.requestLayout();
    }

    // ====================== UI 初始化 ======================
    private void setupListView() {
        scriptAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, scriptList);
        lvScripts.setAdapter(scriptAdapter);

        // 点击列表项直接运行
        lvScripts.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < scriptList.size()) {
                runFile(scriptList.get(position));
            }
        });

        // 长按删除
        lvScripts.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < scriptList.size()) {
                new AlertDialog.Builder(this)
                        .setTitle("删除脚本")
                        .setMessage(scriptList.get(position))
                        .setPositiveButton("删除", (dialog, which) -> removeScript(position))
                        .setNegativeButton("取消", null)
                        .show();
            }
            return true;
        });
    }

    private void setupButtons() {
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));
        }
        // 这里绝对不碰不存在的 btnStop / btnClear
    }

    private void setupInput() {
        if (etInput == null) return;

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            // 统一处理回车和发送键
            boolean enter = (actionId == EditorInfo.IME_ACTION_DONE) ||
                    (actionId == EditorInfo.IME_ACTION_SEND) ||
                    (actionId == EditorInfo.IME_ACTION_GO) ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN);

            if (enter) {
                String text = etInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    handleInputSend(text);
                    etInput.setText("");
                }
                return true;
            }
            return false;
        });

        if (btnSend != null) {
            btnSend.setOnClickListener(v -> {
                String text = etInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    handleInputSend(text);
                    etInput.setText("");
                }
            });
        }
    }

    // ★ 核心：智能输入处理（判断脚本是否在运行）
    private void handleInputSend(String text) {
        if (isScriptRunning.get() && processStdin != null) {
            try {
                processStdin.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                processStdin.flush();
                appendText("$ " + text + "\n");
            } catch (IOException e) {
                appendText("[ERROR] 发送输入失败: " + e.getMessage() + "\n");
            }
        } else {
            runRootCommandStream(text);
        }
    }

    // ====================== Root 初始化与 DNS 修复 ======================
    private void initDnsFix() {
        String resolvPath = RUNTIME_DIR + "/resolv.conf";
        String dnsContent = "nameserver 114.114.114.114\\nnameserver 8.8.8.8\\n";
        String cmd = "echo -e \"" + dnsContent + "\" > " + resolvPath + " && chmod 644 " + resolvPath;
        ShellResult result = runRootCommand(cmd, 10000);
        if (result.success) {
            appendText("[+] DNS 配置已注入\n");
        } else {
            appendText("[ERROR] DNS 注入失败: " + result.stderr + "\n");
        }
    }

    private String findSu() {
        String[] candidates = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String path : candidates) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    private boolean checkRoot() {
        if (suPath == null) return false;
        ShellResult result = runRootCommand("id", 10000);
        return result.success && (result.stdout.contains("uid=0") || result.stdout.trim().equals("0"));
    }

    private boolean prepareRuntimeDir() {
        String command = "mkdir -p " + shellQuote(RUNTIME_DIR) + " && chmod 755 " + shellQuote(RUNTIME_DIR);
        return runRootCommand(command, 10000).success;
    }

    // ====================== 核心：运行脚本 (PTY) ======================
    private void runFile(String path) {
        if (path == null || path.trim().isEmpty()) return;
        String finalPath = path.startsWith("/") ? path : RUNTIME_DIR + "/" + sanitizeFileName(path);
        executor.execute(() -> runFileReal(finalPath));
    }

    private void runFileReal(String path) {
        synchronized (processLock) {
            stopCurrentProcessInternal();

            if (suPath == null) return;
            appendText("\n--- 开始运行: " + new File(path).getName() + " ---\n");

            String workDir = new File(path).getParent();
            if (workDir == null) workDir = RUNTIME_DIR;

            String busyboxPath = RUNTIME_DIR + "/" + BUSYBOX_NAME;
            String envCmd = "export PATH=" + RUNTIME_DIR + ":/system/bin:/system/xbin:/vendor/bin:$PATH; " +
                    "export TMPDIR=" + RUNTIME_DIR + "; " +
                    "export RESOLV_CONF=" + RUNTIME_DIR + "/resolv.conf; " +
                    "cd " + shellQuote(workDir) + "; ";

            String command = envCmd + shellQuote(busyboxPath) + " script -q -c 'exec " + path + "' /dev/null";

            try {
                ProcessBuilder pb = new ProcessBuilder(suPath, "-c", command);
                pb.redirectErrorStream(false);
                Process process = pb.start();

                processStdin = process.getOutputStream();

                synchronized (processLock) {
                    currentProcess = process;
                    isScriptRunning.set(true);
                }

                Thread stdoutThread = new Thread(() -> readProcessStream(process.getInputStream(), false));
                Thread stderrThread = new Thread(() -> readProcessStream(process.getErrorStream(), true));
                stdoutThread.start();
                stderrThread.start();

                int exitCode = process.waitFor();
                try { stdoutThread.join(1000); } catch (InterruptedException ignored) {}
                try { stderrThread.join(1000); } catch (InterruptedException ignored) {}

                appendText("\n--- 脚本执行结束，退出码: " + exitCode + " ---\n");

            } catch (Exception e) {
                appendText("[EXEC] 启动异常: " + e.getMessage() + "\n");
            } finally {
                synchronized (processLock) {
                    currentProcess = null;
                    processStdin = null;
                    isScriptRunning.set(false);
                }
            }
        }
    }

    private void readProcessStream(InputStream input, boolean isError) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                appendText(isError ? "[STDERR] " + line + "\n" : line + "\n");
            }
        } catch (Exception ignored) {}
    }

    private void stopCurrentProcessInternal() {
        Process process;
        synchronized (processLock) {
            process = currentProcess;
            processStdin = null;
            isScriptRunning.set(false);
            currentProcess = null;
        }
        if (process != null) {
            process.destroy();
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    // ====================== 普通 Root 命令执行 ======================
    private void runRootCommandStream(String cmd) {
        appendText("$ " + cmd + "\n");
        executor.execute(() -> {
            if (suPath == null) return;
            try {
                Process process = Runtime.getRuntime().exec(new String[]{suPath, "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) appendText(line + "\n");
                process.waitFor();
            } catch (Exception e) {
                appendText("[ERROR] 命令执行失败: " + e.getMessage() + "\n");
            }
        });
    }

    private ShellResult runRootCommand(String command, long timeoutMs) {
        if (suPath == null) return new ShellResult(false, "", "suPath == null", -1);
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(suPath, "-c", command);
            pb.redirectErrorStream(false);
            process = pb.start();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            Thread outThread = new Thread(() -> copyStream(process.getInputStream(), stdout));
            Thread errThread = new Thread(() -> copyStream(process.getErrorStream(), stderr));
            outThread.start();
            errThread.start();

            long start = System.currentTimeMillis();
            while (true) {
                try {
                    int exit = process.exitValue();
                    outThread.join(1000);
                    errThread.join(1000);
                    return new ShellResult(exit == 0, stdout.toString("UTF-8"), stderr.toString("UTF-8"), exit);
                } catch (IllegalThreadStateException ignored) {}

                if (System.currentTimeMillis() - start > timeoutMs) {
                    process.destroyForcibly();
                    return new ShellResult(false, stdout.toString("UTF-8"), stderr.toString("UTF-8") + "\nTIMEOUT", -2);
                }
                Thread.sleep(20);
            }
        } catch (Exception e) {
            return new ShellResult(false, "", e.getMessage(), -1);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void copyStream(InputStream input, ByteArrayOutputStream output) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        } catch (Exception ignored) {}
    }

    // ====================== 内置文件安装 ======================
    private void installBuiltinAssets() {
        try {
            byte[] bbBytes = readAsset(BUSYBOX_NAME);
            if (bbBytes != null) {
                File temp = new File(getFilesDir(), BUSYBOX_NAME);
                try (FileOutputStream fos = new FileOutputStream(temp)) { fos.write(bbBytes); }
                copyFileAsRoot(temp, RUNTIME_DIR + "/" + BUSYBOX_NAME);
                temp.delete();
            }
        } catch (Exception e) {
            appendText("[ERROR] Busybox 安装失败: " + e.getMessage() + "\n");
        }

        for (String assetName : BUILTIN_ASSETS) {
            try {
                byte[] bytes = readAsset(assetName);
                if (bytes == null || bytes.length == 0) continue;

                File temp = new File(getFilesDir(), assetName);
                try (FileOutputStream fos = new FileOutputStream(temp)) { fos.write(bytes); }
                String runtimePath = RUNTIME_DIR + "/" + assetName;
                copyFileAsRoot(temp, runtimePath);
                addScript(runtimePath);
                temp.delete();
            } catch (Exception e) {
                appendText("[ERROR] 安装 " + assetName + " 失败: " + e.getMessage() + "\n");
            }
        }
        refreshScriptList();
    }

    private byte[] readAsset(String assetName) throws IOException {
        try (InputStream in = getAssets().open(assetName); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private boolean copyFileAsRoot(File source, String destination) {
        if (suPath == null || !source.exists()) return false;
        String cmd = "cat " + shellQuote(source.getAbsolutePath()) + " > " + shellQuote(destination) +
                " && chmod 755 " + shellQuote(destination);
        return runRootCommand(cmd, 30000).success;
    }

    // ====================== 文件导入 ======================
    private void importSelectedFile(Uri uri) {
        String originalName = getDisplayName(uri);
        String fileName = sanitizeFileName(originalName != null ? originalName : "imported_file");
        File localFile = new File(getFilesDir(), fileName);

        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(localFile)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }

            if (copyFileAsRoot(localFile, RUNTIME_DIR + "/" + fileName)) {
                addScript(RUNTIME_DIR + "/" + fileName);
                appendText("[+] 导入完成: " + fileName + "\n");
            } else {
                appendText("[ERROR] Root 复制失败\n");
            }
        } catch (Exception e) {
            appendText("[ERROR] 导入异常: " + e.getMessage() + "\n");
        } finally {
            if (localFile.exists()) localFile.delete();
        }
    }

    // ====================== 列表管理 ======================
    private void loadScriptList() {
        scriptList.clear();
        Set<String> saved = preferences.getStringSet(PREF_SCRIPT_LIST, null);
        if (saved != null) scriptList.addAll(new HashSet<>(saved));
    }

    private void saveScriptList() {
        preferences.edit().putStringSet(PREF_SCRIPT_LIST, new HashSet<>(scriptList)).apply();
    }

    private void addScript(String path) {
        mainHandler.post(() -> {
            if (!scriptList.contains(path)) {
                scriptList.add(path);
                saveScriptList();
                if (scriptAdapter != null) scriptAdapter.notifyDataSetChanged();
            }
        });
    }

    private void removeScript(int position) {
        if (position >= 0 && position < scriptList.size()) {
            scriptList.remove(position);
            saveScriptList();
            if (scriptAdapter != null) scriptAdapter.notifyDataSetChanged();
        }
    }

    private void refreshScriptList() {
        mainHandler.post(() -> {
            if (scriptAdapter != null) scriptAdapter.notifyDataSetChanged();
        });
    }

    // ====================== 工具类 ======================
    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "imported_file";
        return name.replace("/", "_").replace("\\", "_").replace("\0", "_").replace("..", "_");
    }

    private String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void appendText(String text) {
        if (text == null) return;
        mainHandler.post(() -> {
            if (tvOutput != null) {
                tvOutput.append(text);
                if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopCurrentProcessInternal();
        executor.shutdownNow();
        super.onDestroy();
    }

    private static class ShellResult {
        final boolean success;
        final String stdout;
        final String stderr;
        final int exitCode;

        ShellResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
        }
    }
}
